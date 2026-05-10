StormClientLOS = StormClientLOS or {}

local entriesScratch = {}

local function getEntriesScratch(playerIndex)
    local s = entriesScratch[playerIndex]
    if s == nil then
        s = {}
        entriesScratch[playerIndex] = s
        return s
    end
    for k in pairs(s) do
        s[k] = nil
    end
    return s
end

local function buildReportForPlayer(player, tickInt)
    local playerIndex = player:getPlayerNum()
    local entries = getEntriesScratch(playerIndex)
    local selfSpotted = false

    local list = getCell():getObjectListForLua()
    local n = list:size()
    for i = 0, n - 1 do
        local obj = list:get(i)
        local skip = false
        if instanceof(obj, "IsoPhysicsObject") then
            skip = true
        end
        if not skip and instanceof(obj, "BaseVehicle") then
            skip = true
        end
        if not skip then
            if obj == player then
                selfSpotted = true
            elseif instanceof(obj, "IsoGameCharacter") then
                local isZombie = instanceof(obj, "IsoZombie")
                local skipGrappled = false
                if isZombie and obj:isReanimatedForGrappleOnly() then
                    skipGrappled = true
                end
                if not skipGrappled then
                    local objSquare = obj:getCurrentSquare()
                    if objSquare ~= nil then
                        local couldSee = objSquare:isCouldSee(playerIndex)
                        if isZombie and not couldSee then
                            if obj:couldSeeHeadSquare(player) then
                                couldSee = true
                            end
                        end
                        local canSee = objSquare:isCanSee(playerIndex)
                        if isZombie and not canSee then
                            if obj:canSeeHeadSquare(player) then
                                canSee = true
                            end
                        end
                        if couldSee or canSee then
                            entries[#entries + 1] = {
                                id = obj:getOnlineID(),
                                couldSee = couldSee,
                                canSee = canSee,
                            }
                        end
                    end
                end
            end
        end
    end

    return {
        playerOnlineID = player:getOnlineID(),
        tick = tickInt,
        wallMs = getTimestampMs(),
        selfSpotted = selfSpotted,
        truncated = false,
        entries = entries,
    }
end

local function sendReports(numberTicks)
    if numberTicks == nil then
        return
    end
    local tickInt = math.floor(numberTicks)
    for i = 0, 3 do
        local player = getSpecificPlayer(i)
        if player ~= nil then
            if player:getCurrentSquare() ~= nil then
                if not player:isAsleep() then
                    local args = buildReportForPlayer(player, tickInt)
                    sendClientCommand(player, "storm_los", "report", args)
                end
            end
        end
    end
end

Events.OnTick.Add(sendReports)
