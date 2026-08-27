-- Storm server-side checkWeapon: ISWorldObjectContextMenu lives in media/lua/client/, and
-- the dedicated server loads that directory checksum-only (GameServer.java's
-- LuaManager.LoadDirBase("client", true)) -- the files are hashed but never executed, so the
-- global is nil on the server.
--
-- Shared timed actions still call it from complete(), which in MP runs server-side:
-- ISDestroyStuffAction:complete() line 312 calls checkWeapon whenever the sledge swing
-- degrades the weapon (sledge:damageCheck(0,2,false) == true). Indexing the nil global raises
-- a Lua error, LuaCaller.protectedCallBoolean returns null, NetTimedAction.perform NPEs on the
-- unboxing, and ActionManager rejects a destruction the server has already committed.
--
-- checkWeapon's own body is already server-aware (it has an isServer() branch), so the fix is
-- just to make the global exist in the server Lua state. Note that MP clients also load
-- media/lua/server (GameLoadingState), hence both guards below: on a client the real
-- ISWorldObjectContextMenu is already defined and must not be clobbered.

if not isServer() then
    return
end

ISWorldObjectContextMenu = ISWorldObjectContextMenu or {}

if ISWorldObjectContextMenu.checkWeapon then
    return
end

ISWorldObjectContextMenu.checkWeapon = function(chr)
    local weapon = chr:getPrimaryHandItem()
    if weapon and weapon:getCondition() > 0 then
        return
    end

    chr:removeFromHands(weapon)

    local replacement = chr:getInventory():getBestWeapon(chr:getDescriptor())
    if
        replacement
        and replacement ~= chr:getPrimaryHandItem()
        and replacement:getCondition() > 0
    then
        chr:setPrimaryHandItem(replacement)
        if replacement:isTwoHandWeapon() and not chr:getSecondaryHandItem() then
            chr:setSecondaryHandItem(replacement)
        end
    end

    -- Vanilla's isServer() branch sends 'dirtyUI', but client/ServerCommands.lua only registers
    -- Commands.ui.DirtyUI and the dispatcher does an exact key lookup, so the vanilla spelling
    -- is silently dropped.
    sendServerCommand(chr, "ui", "DirtyUI", {})
end
