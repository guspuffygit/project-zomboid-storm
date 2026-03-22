-- Preserve any hooks registered by mods that loaded before Storm
WorldMapOptions_visibleOptionsHooks = WorldMapOptions_visibleOptionsHooks or {}

function WorldMapOptions:getVisibleOptions()
    local result = {}
    if self.showAllOptions then
        for i = 1, self.map.mapAPI:getOptionCount() do
            local option = self.map.mapAPI:getOptionByIndex(i - 1)
            if isClient() or not self:isMultiplayerOption(option:getName()) then
                table.insert(result, option)
            end
        end
        for _, hook in ipairs(WorldMapOptions_visibleOptionsHooks) do
            hook(result)
        end
        table.sort(result, function(a,b) return not string.sort(a:getName(), b:getName()) end)
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

    for _, hook in ipairs(WorldMapOptions_visibleOptionsHooks) do
        hook(result)
    end

    table.sort(result, function(a,b) return not string.sort(a:getName(), b:getName()) end)
    return result
end

function WorldMapOptions:synchUI()
    local showAllOptions = false
    if getDebug() or (isClient() and (getAccessLevel() == "admin")) then
        showAllOptions = true
    end

    local visibleOptions = self:getVisibleOptions()
    local boolCount = 0
    for _, opt in ipairs(visibleOptions) do
        if opt:getType() == "boolean" then boolCount = boolCount + 1 end
    end

    if showAllOptions ~= self.showAllOptions
        or self.screenHeight ~= getCore():getScreenHeight()
        or boolCount ~= (self._lastBoolCount or -1) then
        local children = {}
        for k, v in pairs(self:getChildren()) do table.insert(children, v) end
        for _, child in ipairs(children) do self:removeChild(child) end
        self:createChildren()
        self._lastBoolCount = boolCount
    end

    visibleOptions = self:getVisibleOptions()
    for i, option in ipairs(visibleOptions) do
        if option:getType() == "boolean" and self.tickBoxes[i] then
            self.tickBoxes[i]:setSelected(1, option:getValue())
        end
        if option:getType() == "double" and self.doubleBoxes[i] then
            self.doubleBoxes[i]:setText(option:getValueAsString())
        end
    end
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
