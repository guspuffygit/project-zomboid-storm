require("TimedActions/ISInventoryTransferAction")

-- Storm Transfer Fix: replaces the vanilla byte-ID Transaction system with UUID-keyed
-- transactions sent via sendClientCommand. Covers player inventory, bags, world object
-- containers, vehicle part containers, and the floor (drops and pickups). This fixes:
--   Root Cause #4: Byte ID wraparound causing collisions across players
--   Root Cause #5: Vacuous truth on ID 0 when createItemTransaction fails
--
-- Each item still gets its own server-side transaction. Since PZ 42.20.0 the client
-- merges same-type light items into one queued batch (checkQueueList runs client-side),
-- so a batch fans out into one UUID per member item. Dead body transfers and corpse
-- items without a world object use the vanilla system unchanged.

local MODULE = "StormTransfer"

---------------------------------------------------------------------------
-- Client-side transaction state table
---------------------------------------------------------------------------

local stormTransactions = {} -- uuid -> { state = "pending"|"accepted"|"done"|"rejected", duration = -1, startTime = timestamp }

---------------------------------------------------------------------------
-- Server command listener
---------------------------------------------------------------------------

local function onServerCommand(module, command, args)
    if module ~= MODULE then
        return
    end
    if command ~= "result" then
        return
    end

    local uuid = args.uuid
    if not uuid then
        return
    end

    local t = stormTransactions[uuid]
    if not t then
        return
    end

    local status = args.status
    if status == "accepted" then
        t.state = "accepted"
        if args.duration then
            t.duration = args.duration
        end
    elseif status == "done" then
        t.state = "done"
    elseif status == "rejected" then
        t.state = "rejected"
    end
end

Events.OnServerCommand.Add(onServerCommand)

---------------------------------------------------------------------------
-- Helpers
---------------------------------------------------------------------------

-- Build a resolvable container reference string that the server can parse
-- to find the same container. Uses identifiers that are consistent on both
-- client and server (item IDs, spatial coords, vehicle IDs).
local function getContainerRef(container, character)
    -- Player main inventory
    if container == character:getInventory() then
        return "player"
    end
    -- Bag in player inventory (InventoryContainer item with a numeric ID)
    -- Must check isInCharacterInventory to exclude placed containers on the ground,
    -- which also have a containingItem but need the world object path instead.
    local containingItem = container:getContainingItem()
    if containingItem and container:isInCharacterInventory(character) then
        return "bag:" .. tostring(containingItem:getID())
    end
    -- Vehicle part container
    local part = container:getVehiclePart()
    if part then
        local vehicle = part:getVehicle()
        if vehicle then
            return "vehicle:" .. tostring(vehicle:getId()) .. ":" .. tostring(part:getIndex())
        end
    end
    -- World object container (IsoObject on a grid square)
    local parent = container:getParent()
    if parent and parent:getSquare() then
        local sq = parent:getSquare()
        local objects = sq:getObjects()
        local objectIndex = -1
        for i = 0, objects:size() - 1 do
            if objects:get(i) == parent then
                objectIndex = i
                break
            end
        end
        if objectIndex >= 0 then
            local containerIndex = parent:getContainerIndex(container)
            return "object:"
                .. tostring(sq:getX())
                .. ":"
                .. tostring(sq:getY())
                .. ":"
                .. tostring(sq:getZ())
                .. ":"
                .. tostring(objectIndex)
                .. ":"
                .. tostring(containerIndex)
        end
    end
    -- Placed container on the ground (bag/crate placed as world item).
    -- These have getParent() == null but getContainingItem():getWorldItem() gives the IsoWorldInventoryObject.
    if containingItem and containingItem:getWorldItem() then
        local worldItem = containingItem:getWorldItem()
        local sq = worldItem:getSquare()
        if sq then
            local objects = sq:getObjects()
            local objectIndex = -1
            for i = 0, objects:size() - 1 do
                if objects:get(i) == worldItem then
                    objectIndex = i
                    break
                end
            end
            if objectIndex >= 0 then
                return "worlditem:"
                    .. tostring(sq:getX())
                    .. ":"
                    .. tostring(sq:getY())
                    .. ":"
                    .. tostring(sq:getZ())
                    .. ":"
                    .. tostring(objectIndex)
            end
        end
    end
    return nil -- unsupported (dead body) -> vanilla handles it
end

-- Floor refs are item-dependent: pickups identify the item's world object by its
-- square plus the inventory item id; drops send a bare "floor" marker and the server
-- picks the drop square from the player's authoritative position (vanilla behavior).
local function getSourceRef(container, character, item)
    if container:getType() == "floor" then
        if not item then
            return nil
        end
        local worldItem = item:getWorldItem()
        if not worldItem or not worldItem:getSquare() then
            -- corpse items and other floor entries without a world object -> vanilla
            return nil
        end
        local sq = worldItem:getSquare()
        return "floorItem:"
            .. tostring(sq:getX())
            .. ":"
            .. tostring(sq:getY())
            .. ":"
            .. tostring(sq:getZ())
            .. ":"
            .. tostring(item:getID())
    end
    return getContainerRef(container, character)
end

local function getDestRef(container, character, item)
    if container:getType() == "floor" then
        return "floor"
    end
    return getContainerRef(container, character)
end

local function shouldUseStormTransfer(src, dest, character, item)
    if not isClient() then
        return false
    end
    return getSourceRef(src, character, item) ~= nil and getDestRef(dest, character, item) ~= nil
end

local function createStormTransaction(character, item, srcContainer, destContainer)
    local uuid = getRandomUUID()
    stormTransactions[uuid] = {
        state = "pending",
        duration = -1,
        startTime = getTimestampMs(),
    }

    sendClientCommand(character, MODULE, "transferItem", {
        uuid = uuid,
        itemId = item:getID(),
        srcContainerRef = getSourceRef(srcContainer, character, item),
        destContainerRef = getDestRef(destContainer, character, item),
    })

    return uuid
end

local function isStormTransactionDone(uuid)
    local t = stormTransactions[uuid]
    return t ~= nil and t.state == "done"
end

local function isStormTransactionRejected(uuid)
    local t = stormTransactions[uuid]
    return t ~= nil and t.state == "rejected"
end

local function getStormTransactionDuration(uuid)
    local t = stormTransactions[uuid]
    if t and t.duration and t.duration > 0 then
        return t.duration
    end
    return -1
end

local function cancelStormTransaction(character, uuid)
    if uuid and stormTransactions[uuid] then
        sendClientCommand(character, MODULE, "cancelTransfer", { uuid = uuid })
        stormTransactions[uuid] = nil
    end
end

local function cleanupStormTransaction(uuid)
    if uuid then
        stormTransactions[uuid] = nil
    end
end

-- Timeout threshold in milliseconds (safety net for lost packets)
local STORM_TIMEOUT_MS = 15000

local function isStormTransactionTimedOut(uuid)
    local t = stormTransactions[uuid]
    if not t then
        return false
    end
    return (getTimestampMs() - t.startTime) > STORM_TIMEOUT_MS
end

-- One UUID per member of the current batch. Floor pickups have per-item source refs;
-- a later member without a world object cannot be authorized and is skipped (stays
-- put) rather than failing the whole batch. Member 1 is always sent so an unresolvable
-- ref surfaces as a server reject, like the pre-batch behavior.
local function createStormBatch(character, items, srcContainer, destContainer)
    local transfers = {}
    for i, item in ipairs(items) do
        if i == 1 or getSourceRef(srcContainer, character, item) ~= nil then
            table.insert(transfers, {
                uuid = createStormTransaction(character, item, srcContainer, destContainer),
                item = item,
            })
        end
    end
    return transfers
end

---------------------------------------------------------------------------
-- Override start()
---------------------------------------------------------------------------

local _originalStart = ISInventoryTransferAction.start

function ISInventoryTransferAction:start()
    if
        not shouldUseStormTransfer(self.srcContainer, self.destContainer, self.character, self.item)
    then
        _originalStart(self)
        return
    end

    -- Replicate vanilla start() logic (ISInventoryTransferAction.lua lines 248-303)
    if self:isAlreadyTransferred(self.item) then
        self.selectedContainer = nil
        self.action:setTime(0)
        return
    end

    if self.dontAdd then
        self.selectedContainer = nil
        self.action:setTime(0)
        return
    end

    if self.character:isPlayerMoving() then
        self.maxTime = self.maxTime * 1.5
        self.action:setTime(self.maxTime)
    end

    -- Stop microwave working when putting new stuff in it
    if
        self.destContainer
        and self.destContainer:getType() == "microwave"
        and self.destContainer:getParent()
        and self.destContainer:getParent():Activated()
    then
        self.destContainer:getParent():setActivated(false)
    end
    if
        self.srcContainer
        and self.srcContainer:getType() == "microwave"
        and self.srcContainer:getParent()
        and self.srcContainer:getParent():Activated()
    then
        self.srcContainer:getParent():setActivated(false)
    end

    self:playSourceContainerOpenSound()
    self:playDestContainerOpenSound()

    if ISInventoryTransferAction.putSoundContainer ~= self.destContainer then
        ISInventoryTransferAction.putSoundTime = 0
    end

    if self.item and self.item:getType() == "Animal" then
        -- Hack: breed sound played by IsoGridSquare.AddWorldInventoryItem()
    elseif
        not ISInventoryTransferAction.putSound
        or not self.character:getEmitter():isPlaying(ISInventoryTransferAction.putSound)
    then
        local soundName = self:getTransferStartSoundName()
        if soundName then
            ISInventoryTransferAction.putSoundContainer = self.destContainer
            if
                ISInventoryTransferAction.putSoundTime + ISInventoryTransferAction.putSoundDelay
                < getTimestamp()
            then
                ISInventoryTransferAction.putSoundTime = getTimestamp()
                ISInventoryTransferAction.putSound =
                    self.character:getEmitter():playSound(soundName)
            end
        end
    end

    self.loopSound = self.character:getEmitter():playSound("RummageInInventory")
    self.loopSoundNoTrigger = true

    -- Wait for server to tell us when the transfer is done
    self.action:setWaitForFinished(true)

    -- UUID-based transactions — checkQueueList mirrors vanilla's start(): it merges queued
    -- same-type light items into queueList[1] before the batch is authorized.
    local items = { self.item }
    self:checkQueueList()
    if #self.queueList > 0 then
        items = self.queueList[1].items
    end
    self._stormTransfer = true
    self._stormTransfers =
        createStormBatch(self.character, items, self.srcContainer, self.destContainer)

    self:startActionAnim()
    self.started = true
end

---------------------------------------------------------------------------
-- Override update()
---------------------------------------------------------------------------

local _originalUpdate = ISInventoryTransferAction.update

function ISInventoryTransferAction:update()
    if not self._stormTransfer then
        _originalUpdate(self)
        return
    end

    -- Replicate vanilla update() non-transaction logic (lines 128-156)

    -- Unhappiness from stripping corpse items
    if
        self.character
        and (not self.character:hasTrait(CharacterTrait.DESENSITIZED))
        and self.srcContainer
        and self.srcContainer:getType()
        and (
            self.srcContainer:getType() == "inventoryfemale"
            or self.srcContainer:getType() == "inventorymale"
        )
    then
        local rate = getGameTime():getMultiplier()
        if self.character:hasTrait(CharacterTrait.COWARDLY) then
            rate = rate * 2
        elseif self.character:hasTrait(CharacterTrait.BRAVE) then
            rate = rate / 2
        end
        local stats = self.character:getStats()
        stats:add(CharacterStat.UNHAPPINESS, rate / 100)
    end

    -- Blood fear stress
    if
        self.character
        and self.character:hasTrait(CharacterTrait.HEMOPHOBIC)
        and self.item
        and self.item:getBloodLevel() > 0
    then
        local rate = self.item:getBloodLevelAdjustedLow() * getGameTime():getMultiplier()
        local stats = self.character:getStats()
        stats:add(CharacterStat.STRESS, rate / 10000)
    end

    -- Reopen the correct container
    if self.selectedContainer then
        if self.selectedContainer:getParent() and not self.character:isSittingOnFurniture() then
            self.character:faceThisObject(self.selectedContainer:getParent())
        end
        if self.character:shouldBeTurning() then
            getPlayerLoot(self.character:getPlayerNum()):setForceSelectedContainer(
                self.selectedContainer
            )
        end
        getPlayerLoot(self.character:getPlayerNum()):selectButtonForContainer(
            self.selectedContainer
        )
    end

    self.item:setJobDelta(self.action:getJobDelta())
    self.character:setMetabolicTarget(Metabolics.LightWork)

    -- Storm transaction state checks (replaces vanilla byte-ID checks). The action
    -- completes when every batch member is done; any reject/timeout/vanished item
    -- stops it, and stop() cancels the batch's outstanding members server-side.
    local transfers = self._stormTransfers or {}
    local allDone = true
    for _, transfer in ipairs(transfers) do
        if
            isStormTransactionRejected(transfer.uuid)
            or isStormTransactionTimedOut(transfer.uuid)
        then
            self:forceStop()
            return
        end
        if not isStormTransactionDone(transfer.uuid) then
            allDone = false
            if not self.srcContainer:contains(transfer.item) then
                self:forceStop()
                return
            end
        end
    end
    if allDone then
        for _, transfer in ipairs(transfers) do
            cleanupStormTransaction(transfer.uuid)
        end
        self._stormTransfers = {}
        self:forceComplete()
        return
    end

    -- Duration from server (replaces getItemTransactionDuration); the slowest batch
    -- member drives the progress bar
    if self.maxTime == -1 then
        local duration = -1
        for _, transfer in ipairs(transfers) do
            local d = getStormTransactionDuration(transfer.uuid)
            if d > duration then
                duration = d
            end
        end
        if duration > 0 then
            self.maxTime = duration
            self.action:setTime(self.maxTime)
        end
    end
end

---------------------------------------------------------------------------
-- Override perform()
---------------------------------------------------------------------------

local _originalPerform = ISInventoryTransferAction.perform

function ISInventoryTransferAction:perform()
    if not self._stormTransfer then
        _originalPerform(self)
        return
    end

    -- transferItem() is NOT called: the server already moved every batch member; the
    -- client only plays feedback.
    self.item:setJobDelta(0.0)
    local queuedItem = table.remove(self.queueList, 1)

    if self.selectedContainer then
        getPlayerLoot(self.character:getPlayerNum()):selectButtonForContainer(
            self.selectedContainer
        )
    end

    if queuedItem ~= nil then
        for i, item in ipairs(queuedItem.items) do
            self.item = item
            if not self:isValid() then
                self.queueList = {}
                break
            end
            self:playTransferCompleteSound(item)
        end
    end

    -- Merge actions queued while this batch was in flight (vanilla runs
    -- checkQueueList here on the client, after the item loop)
    self:checkQueueList()

    for _, transfer in ipairs(self._stormTransfers or {}) do
        cleanupStormTransaction(transfer.uuid)
    end
    self._stormTransfers = {}

    -- If we still have items to transfer, reset the action for the next batch
    if #self.queueList > 0 then
        local nextItem = self.queueList[1]
        self.item = nextItem.items[1]
        local time = nextItem.time
        if self:isAlreadyTransferred(self.item) then
            time = 0
        end
        if self.allowMissingItems and not self.srcContainer:contains(self.item) then
            time = 0
        end
        self.action:reset()
        -- UUID-based transactions for the next batch
        self._stormTransfers =
            createStormBatch(self.character, nextItem.items, self.srcContainer, self.destContainer)
        self.maxTime = time
        self.action:setTime(tonumber(time))
        self:resetJobDelta()
        self:startActionAnim()
    else
        -- Queue empty — clean up
        self:playSourceContainerCloseSound()
        self:playDestContainerCloseSound()
        self:stopLoopingSound()

        self.action:stopTimedActionAnim()
        self.action:setLoopedAction(false)
        self.action:setWaitForFinished(false)

        if self.onCompleteFunc then
            local args = self.onCompleteArgs
            self.onCompleteFunc(
                args[1],
                args[2],
                args[3],
                args[4],
                args[5],
                args[6],
                args[7],
                args[8]
            )
        end

        ISBaseTimedAction.perform(self)
        self.started = false
        self._stormTransfer = false
    end

    if instanceof(self.item, "Radio") then
        self.character:updateEquippedRadioFreq()
    end

    ISInventoryPage.renderDirty = true
end

---------------------------------------------------------------------------
-- Override stop()
---------------------------------------------------------------------------

local _originalStop = ISInventoryTransferAction.stop

function ISInventoryTransferAction:stop()
    if self._stormTransfer then
        for _, transfer in ipairs(self._stormTransfers or {}) do
            cancelStormTransaction(self.character, transfer.uuid)
        end
        self._stormTransfer = false
        self._stormTransfers = nil
    end

    _originalStop(self)
end
