-- Injected into the client Lua env by StormEventHandler on OnZomboidGlobalsLoadEvent, i.e.
-- immediately after every LuaManager.LoadDirBase(), so vanilla UI classes already exist and
-- the patch re-applies over a reloaded ISScoreboard / ISMiniScoreboardUI. Shipping it in
-- storm.jar instead of media/lua/client keeps it out of the server's client-file checksum.
--
-- Client half of the "which players run Storm" column (server half: StormPlayersHandler):
--  * announces this client's Storm version to the server (StormPlayers.hello) on the first
--    tick after OnGameStart (IngameState.enter fires OnGameStart *before* it sends
--    PlayerConnect, so a hello sent from the event itself reaches the server before it has a
--    player for the connection and GameServer.receiveClientCommand drops it);
--  * for admins holding Capability.SeePlayersConnected, asks the server (StormPlayers.request)
--    for the { [username] = version } list whenever the scoreboard refreshes, and caches the
--    async StormPlayers.list reply;
--  * draws "Storm <version>" / "No Storm" on every row of the ESC-menu PLAYERS scoreboard
--    (ISScoreboard) and the Admin Panel Mini Scoreboard (ISMiniScoreboardUI).
-- Against a server without Storm nothing is drawn: the column only appears once the server
-- has answered at least once.

StormPlayers = StormPlayers or {}
local Players = StormPlayers

local MODULE = "StormPlayers"
local COMMAND_HELLO = "hello"
local COMMAND_REQUEST = "request"
local COMMAND_LIST = "list"

local REQUEST_THROTTLE_MS = 1000

local LABEL_STORM = "Storm %s"
local LABEL_NO_STORM = "No Storm"

Players.versions = Players.versions or {}
Players.serverAnswered = Players.serverAnswered or false
Players.lastRequestAt = Players.lastRequestAt or 0

local function canSeePlayers()
    local player = getPlayer()
    local role = player and player:getRole()
    return role ~= nil and role:hasCapability(Capability.SeePlayersConnected)
end

function Players.sendHello()
    if not isClient() then
        return
    end
    sendClientCommand(MODULE, COMMAND_HELLO, { version = Storm.getVersion() })
end

function Players.requestList(force)
    if not isClient() or not canSeePlayers() then
        return
    end
    local now = getTimestampMs()
    if not force and now - Players.lastRequestAt < REQUEST_THROTTLE_MS then
        return
    end
    Players.lastRequestAt = now
    sendClientCommand(MODULE, COMMAND_REQUEST, {})
end

function Players.reset()
    Players.versions = {}
    Players.serverAnswered = false
    Players.lastRequestAt = 0
end

--- Label + colour for a scoreboard row, or nil while the server has not answered yet.
function Players.labelFor(username)
    if not Players.serverAnswered or not username then
        return nil
    end
    local version = Players.versions[username]
    if version then
        return string.format(LABEL_STORM, tostring(version)), 0.45, 0.9, 0.45
    end
    return LABEL_NO_STORM, 0.6, 0.6, 0.6
end

--- Draw the label right-aligned so its right edge sits at rightX; returns the label width.
function Players.drawLabel(listbox, username, rightX, y, rowHeight, font)
    local label, r, g, b = Players.labelFor(username)
    if not label then
        return 0
    end
    font = font or UIFont.Small
    local width = getTextManager():MeasureStringX(font, label)
    local fontHeight = getTextManager():getFontHeight(font)
    listbox:drawText(label, rightX - width, y + (rowHeight - fontHeight) / 2, r, g, b, 0.9, font)
    return width
end

local function onHelloTick()
    Events.OnTick.Remove(Players.onHelloTick)
    Players.onHelloTick = nil
    Players.sendHello()
end

local function onGameStart()
    if Players.onHelloTick then
        Events.OnTick.Remove(Players.onHelloTick)
    end
    Players.onHelloTick = onHelloTick
    Events.OnTick.Add(onHelloTick)
end

local function onScoreboardUpdate()
    Players.requestList(false)
end

local function onServerCommand(module, command, args)
    if module ~= MODULE or command ~= COMMAND_LIST then
        return
    end
    local versions = {}
    if args then
        for username, version in pairs(args) do
            versions[username] = version
        end
    end
    Players.versions = versions
    Players.serverAnswered = true
end

local function onDisconnect()
    Players.reset()
end

-- This file re-runs after every LoadDirBase; swap handlers instead of stacking them.
local function rebind(eventName, key, handler)
    local event = Events[eventName]
    if not event then
        return
    end
    if Players[key] then
        event.Remove(Players[key])
    end
    Players[key] = handler
    event.Add(handler)
end

rebind("OnGameStart", "onGameStart", onGameStart)
rebind("OnScoreboardUpdate", "onScoreboardUpdate", onScoreboardUpdate)
rebind("OnServerCommand", "onServerCommand", onServerCommand)
rebind("OnDisconnect", "onDisconnect", onDisconnect)

-- ESC menu -> PLAYERS (ISScoreboard). Rows carry the display name only; stash the username
-- on each row so the column can look it up, then draw the label just left of the ping.
if ISScoreboard then
    ISScoreboard.stormOriginalFillList = ISScoreboard.stormOriginalFillList or ISScoreboard.fillList
    ISScoreboard.stormOriginalDrawMap = ISScoreboard.stormOriginalDrawMap or ISScoreboard.drawMap
    local ORIGINAL_FILL_LIST = ISScoreboard.stormOriginalFillList
    local ORIGINAL_DRAW_MAP = ISScoreboard.stormOriginalDrawMap

    function ISScoreboard:fillList(usernames, displayNames, steamIDs, pingValues)
        ORIGINAL_FILL_LIST(self, usernames, displayNames, steamIDs, pingValues)
        local byDisplayName = {}
        for i = 0, usernames:size() - 1 do
            local displayName = displayNames:get(i)
            if byDisplayName[displayName] == nil then
                byDisplayName[displayName] = usernames:get(i)
            end
        end
        for _, row in ipairs(self.listbox.items) do
            row.item.username = byDisplayName[row.text]
        end
    end

    function ISScoreboard:drawMap(y, item, alt)
        local rowTop = y
        local rowBottom = ORIGINAL_DRAW_MAP(self, y, item, alt)
        if ISScoreboard.isAdmin and item.item then
            local pingWidth =
                getTextManager():MeasureStringX(UIFont.Large, getText("UI_Ping", item.item.ping))
            local rightX = self:getWidth() - 24 - pingWidth - 16
            Players.drawLabel(
                self,
                item.item.username,
                rightX,
                rowTop,
                item.height or self.itemheight,
                UIFont.Small
            )
        end
        return rowBottom
    end
end

-- Admin Panel -> Mini Scoreboard (ISMiniScoreboardUI). Rows already carry item.username.
if ISMiniScoreboardUI then
    ISMiniScoreboardUI.stormOriginalDrawPlayers = ISMiniScoreboardUI.stormOriginalDrawPlayers
        or ISMiniScoreboardUI.drawPlayers
    local ORIGINAL_DRAW_PLAYERS = ISMiniScoreboardUI.stormOriginalDrawPlayers

    function ISMiniScoreboardUI:drawPlayers(y, item, alt)
        local rowTop = y
        local rowBottom = ORIGINAL_DRAW_PLAYERS(self, y, item, alt)
        if item.item then
            local scrollbarWidth = 0
            if self.vscroll and self.vscroll:isVisible() then
                scrollbarWidth = self.vscroll:getWidth()
            end
            Players.drawLabel(
                self,
                item.item.username,
                self:getWidth() - 10 - scrollbarWidth,
                rowTop,
                self.itemheight,
                self.font or UIFont.Small
            )
        end
        return rowBottom
    end
end
