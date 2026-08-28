-- Injected into the client Lua env by StormEventHandler on OnZomboidGlobalsLoadEvent, i.e.
-- immediately after every LuaManager.LoadDirBase() (boot, Core.ResetLua, IngameState.exit),
-- so it re-applies over a freshly reloaded ISAdminPowerUI. Shipping it in storm.jar instead
-- of media/lua/client keeps it out of the server's client-file checksum and lets it reach
-- players through the Storm core self-update channel without a workshop publish.
--
-- Stops the Admin Powers panel from reverting checkboxes while you edit them. Vanilla
-- applies tickbox changes only on Save, but every incoming ExtraInfoPacket (the server
-- re-syncs extra info for any player, not just yours) triggers the RefreshCheats event,
-- and ISAdminPanelUI.OnRoleUpdated responds by calling ISAdminPowerUI:updateAdminPower()
-- -- which clears both tickboxes and rebuilds them from the player's live state. Any tick
-- you haven't saved yet is wiped, so boxes visibly untick themselves under the cursor on
-- a busy server.
--
-- Remember which options the user actually clicked (onTicked is an empty vanilla stub)
-- and re-apply just those after each rebuild. Untouched boxes still track live state, so
-- a cheat genuinely toggled elsewhere still shows through. Pending ticks are discarded on
-- open, save, and close.

if not ISAdminPowerUI then
    return
end

-- kept on the class so a reload of this file re-wraps vanilla instead of stacking wrappers
ISAdminPowerUI.stormOriginalUpdateAdminPower = ISAdminPowerUI.stormOriginalUpdateAdminPower
    or ISAdminPowerUI.updateAdminPower
ISAdminPowerUI.stormOriginalOnClick = ISAdminPowerUI.stormOriginalOnClick or ISAdminPowerUI.onClick
ISAdminPowerUI.stormOriginalOnOpenPanel = ISAdminPowerUI.stormOriginalOnOpenPanel
    or ISAdminPowerUI.OnOpenPanel

local ORIGINAL_UPDATE_ADMIN_POWER = ISAdminPowerUI.stormOriginalUpdateAdminPower
local ORIGINAL_ON_CLICK = ISAdminPowerUI.stormOriginalOnClick
local ORIGINAL_ON_OPEN_PANEL = ISAdminPowerUI.stormOriginalOnOpenPanel

function ISAdminPowerUI:onTicked(index, selected, arg1, arg2, tickBox)
    local options = (tickBox == self.tickBoxLeft) and self.optionsLeft or self.optionsRight
    local option = options and options[index]
    if option then
        self.stormPendingTicks = self.stormPendingTicks or {}
        self.stormPendingTicks[option.id] = selected
    end
end

function ISAdminPowerUI:updateAdminPower()
    ORIGINAL_UPDATE_ADMIN_POWER(self)
    local pending = self.stormPendingTicks
    if not pending then
        return
    end
    for i, option in ipairs(self.optionsLeft) do
        if pending[option.id] ~= nil then
            self.tickBoxLeft:setSelected(i, pending[option.id])
        end
    end
    for i, option in ipairs(self.optionsRight) do
        if pending[option.id] ~= nil then
            self.tickBoxRight:setSelected(i, pending[option.id])
        end
    end
end

function ISAdminPowerUI:onClick(button)
    self.stormPendingTicks = nil
    return ORIGINAL_ON_CLICK(self, button)
end

function ISAdminPowerUI.OnOpenPanel()
    if ISAdminPowerUI.instance then
        ISAdminPowerUI.instance.stormPendingTicks = nil
    end
    return ORIGINAL_ON_OPEN_PANEL()
end
