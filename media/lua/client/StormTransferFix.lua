require "TimedActions/ISInventoryTransferAction"

-- Storm Transfer Fix: replaces the vanilla byte-ID Transaction system with UUID-keyed
-- transactions sent via sendClientCommand. Covers player inventory, bags, world object
-- containers, and vehicle part containers. This fixes:
--   Root Cause #4: Byte ID wraparound causing collisions across players
--   Root Cause #5: Vacuous truth on ID 0 when createItemTransaction fails
--
-- Each item still gets its own transaction (no batching), keeping behavior as close
-- to vanilla as possible. Floor and dead body transfers use the vanilla system unchanged.

local MODULE = "StormTransfer"

---------------------------------------------------------------------------
-- Client-side transaction state table
---------------------------------------------------------------------------

local stormTransactions = {} -- uuid -> { state = "pending"|"accepted"|"done"|"rejected", duration = -1, startTime = timestamp }

---------------------------------------------------------------------------
-- Server command listener
---------------------------------------------------------------------------

local function onServerCommand(module, command, args)
    if module ~= MODULE then return end
    if command ~= "result" then return end

    local uuid = args.uuid
    if not uuid then return end

    local t = stormTransactions[uuid]
    if not t then return end

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
            return "object:" .. tostring(sq:getX()) .. ":" .. tostring(sq:getY()) .. ":" .. tostring(sq:getZ()) .. ":" .. tostring(objectIndex) .. ":" .. tostring(containerIndex)
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
                return "worlditem:" .. tostring(sq:getX()) .. ":" .. tostring(sq:getY()) .. ":" .. tostring(sq:getZ()) .. ":" .. tostring(objectIndex)
            end
        end
    end
    return nil -- unsupported (floor, dead body) -> vanilla handles it
end

local function shouldUseStormTransfer(src, dest, character)
    if not isClient() then return false end
    return getContainerRef(src, character) ~= nil
       and getContainerRef(dest, character) ~= nil
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
        srcContainerRef = getContainerRef(srcContainer, character),
        destContainerRef = getContainerRef(destContainer, character),
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
    if t and t.duration and t.duration > 0 then return t.duration end
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
    if not t then return false end
    return (getTimestampMs() - t.startTime) > STORM_TIMEOUT_MS
end

---------------------------------------------------------------------------
-- Override start()
---------------------------------------------------------------------------

local _originalStart = ISInventoryTransferAction.start

function ISInventoryTransferAction:start()
    if not shouldUseStormTransfer(self.srcContainer, self.destContainer, self.character) then
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
    if self.destContainer and self.destContainer:getType() == "microwave" and self.destContainer:getParent() and self.destContainer:getParent():Activated() then
        self.destContainer:getParent():setActivated(false)
    end
    if self.srcContainer and self.srcContainer:getType() == "microwave" and self.srcContainer:getParent() and self.srcContainer:getParent():Activated() then
        self.srcContainer:getParent():setActivated(false)
    end

    self:playSourceContainerOpenSound()
    self:playDestContainerOpenSound()

    if ISInventoryTransferAction.putSoundContainer ~= self.destContainer then
        ISInventoryTransferAction.putSoundTime = 0
    end

    if self.item and self.item:getType() == "Animal" then
        -- Hack: breed sound played by IsoGridSquare.AddWorldInventoryItem()
    elseif not ISInventoryTransferAction.putSound or not self.character:getEmitter():isPlaying(ISInventoryTransferAction.putSound) then
        local soundName = self:getTransferStartSoundName()
        if soundName then
            ISInventoryTransferAction.putSoundContainer = self.destContainer
            if ISInventoryTransferAction.putSoundTime + ISInventoryTransferAction.putSoundDelay < getTimestamp() then
                ISInventoryTransferAction.putSoundTime = getTimestamp()
                ISInventoryTransferAction.putSound = self.character:getEmitter():playSound(soundName)
            end
        end
    end

    self.loopSound = self.character:getEmitter():playSound("RummageInInventory")
    self.loopSoundNoTrigger = true

    -- Wait for server to tell us when the transfer is done
    self.action:setWaitForFinished(true)

    self:startActionAnim()

    -- KEY CHANGE: UUID-based transaction instead of vanilla byte ID
    self._stormTransfer = true
    self._stormUUID = createStormTransaction(self.character, self.item, self.srcContainer, self.destContainer)
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
    if self.character and (not self.character:hasTrait(CharacterTrait.DESENSITIZED)) and self.srcContainer and self.srcContainer:getType() and (self.srcContainer:getType() == "inventoryfemale" or self.srcContainer:getType() == "inventorymale") then
        local rate = getGameTime():getMultiplier()
        if self.character:hasTrait(CharacterTrait.COWARDLY) then rate = rate * 2
        elseif self.character:hasTrait(CharacterTrait.BRAVE) then rate = rate / 2 end
        local stats = self.character:getStats()
        stats:add(CharacterStat.UNHAPPINESS, rate / 100)
    end

    -- Blood fear stress
    if self.character and self.character:hasTrait(CharacterTrait.HEMOPHOBIC) and self.item and self.item:getBloodLevel() > 0 then
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
            getPlayerLoot(self.character:getPlayerNum()):setForceSelectedContainer(self.selectedContainer)
        end
        getPlayerLoot(self.character:getPlayerNum()):selectButtonForContainer(self.selectedContainer)
    end

    self.item:setJobDelta(self.action:getJobDelta())
    self.character:setMetabolicTarget(Metabolics.LightWork)

    -- Storm transaction state checks (replaces vanilla byte-ID checks)
    if isStormTransactionDone(self._stormUUID) then
        cleanupStormTransaction(self._stormUUID)
        self:forceComplete()
    elseif isStormTransactionRejected(self._stormUUID) then
        cleanupStormTransaction(self._stormUUID)
        self:forceStop()
    elseif isStormTransactionTimedOut(self._stormUUID) then
        cleanupStormTransaction(self._stormUUID)
        self:forceStop()
    end

    -- Duration from server (replaces getItemTransactionDuration)
    if self.maxTime == -1 then
        local duration = getStormTransactionDuration(self._stormUUID)
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

    -- Replicate vanilla perform() logic (lines 452-526)
    self:checkQueueList()

    self.item:setJobDelta(0.0)
    local queuedItem = table.remove(self.queueList, 1)

    if self.selectedContainer then
        getPlayerLoot(self.character:getPlayerNum()):selectButtonForContainer(self.selectedContainer)
    end

    if queuedItem ~= nil then
        for i, item in ipairs(queuedItem.items) do
            self.item = item
            if not self:isValid() then
                self.queueList = {}
                break
            end
            -- transferItem() on client: removeItemTransaction(0, false) is no-op, then returns
            self:transferItem(item)
        end
    end

    -- If we still have items to transfer, reset the action for the next item
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
        self.maxTime = time
        self.action:setTime(tonumber(time))
        self:resetJobDelta()
        self:startActionAnim()
        -- KEY CHANGE: UUID-based transaction for next item
        self._stormUUID = createStormTransaction(self.character, self.item, self.srcContainer, self.destContainer)
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
            self.onCompleteFunc(args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8])
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
        cancelStormTransaction(self.character, self._stormUUID)
        self._stormTransfer = false
        self._stormUUID = nil
    end

    _originalStop(self)
end
