function WorldMapOptions:getVisibleOptions()
    local result = {}
    if self.showAllOptions then
        for i = 1, self.map.mapAPI:getOptionCount() do
            local option = self.map.mapAPI:getOptionByIndex(i - 1)
            if isClient() or not self:isMultiplayerOption(option:getName()) then
                table.insert(result, option)
            end
        end
        return result;
    end

    local optionNames = self:getOptionNames()

    for _, optionName in ipairs(optionNames) do
        for i = 1, self.map.mapAPI:getOptionCount() do
            local option = self.map.mapAPI:getOptionByIndex(i - 1)
            if optionName == option:getName() then
                table.insert(result, option)
                break
            end
        end
    end

    table.sort(result, function(a,b) return not string.sort(a:getName(), b:getName()) end)
    return result
end

function WorldMapOptions:getOptionNames()
    local optionNames = {}
    table.insert(optionNames, "Players")

    if isClient() then
        table.insert(optionNames, "RemotePlayers")
        table.insert(optionNames, "PlayerNames")
    end

    table.insert(optionNames, "Symbols")
    table.insert(optionNames, "HighlightStreet")
    table.insert(optionNames, "LargeStreetLabel")
    table.insert(optionNames, "ShowStreetNames")
    table.insert(optionNames, "PlaceNames")

    if getDebug() then
        table.insert(optionNames, "TerrainImage")
    end

    local javaList = self.map.mapAPI:getExtraOptionNames()

    for i = 0, javaList:size() - 1 do
        local val = javaList:get(i)
        table.insert(optionNames, val)
    end

    return optionNames
end
