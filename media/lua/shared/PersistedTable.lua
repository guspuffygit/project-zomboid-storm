PersistedTable = PersistedTable or {};

function PersistedTable:save(fileName, data)
    local file = getFileWriter(fileName, true, false);
    if not file then return end

    for k, v in pairs(data) do
        if type(v) ~= "function" and type(v) ~= "table" then
            file:write(tostring(k) .. "=" .. tostring(v) .. "\n");
        end
    end

    file:close();
end

function PersistedTable:read(fileName)
    local file = getFileReader(fileName, true);
    if not file then return {} end

    local data = {};
    local line = file:readLine();

    while line do
        local k, v = line:match("^(.-)=(.+)$");
        if k then
            data[k] = v;
        end
        line = file:readLine();
    end

    file:close();
    return data;
end
