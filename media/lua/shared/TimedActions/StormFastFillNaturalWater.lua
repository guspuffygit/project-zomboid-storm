require "TimedActions/ISTakeWaterAction"

local NATURAL_WATER_DURATION = 100

local function isNaturalWater(waterObject)
    if not waterObject then return false end
    local props = waterObject:getProperties()
    return props ~= nil and props:has(IsoFlagType.water)
end

local originalGetDuration = ISTakeWaterAction.getDuration

function ISTakeWaterAction:getDuration()
    if self.character:isTimedActionInstant() then
        return 1
    end

    if isNaturalWater(self.waterObject) then
        return NATURAL_WATER_DURATION
    end

    return originalGetDuration(self)
end
