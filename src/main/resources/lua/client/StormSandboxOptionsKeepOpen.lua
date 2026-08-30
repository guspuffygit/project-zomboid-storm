-- Keeps the Sandbox Options window open after "Apply Changes".
--
-- Vanilla ISServerSandboxOptionsUI:onButtonApply() ends with self:destroy(), so every
-- tweak costs a full reopen: re-navigating to the page and re-finding the option. Admins
-- iterate on these values (apply, watch the world, adjust), so the window closing on
-- apply is pure friction -- "Close" already exists for the case where you are done.
--
-- Suppressing destroy() for the duration of the vanilla handler keeps the rest of its
-- work (settingsFromUI, sendToServer, parseDistributions, StoryClutter) intact, including
-- any steps a future game update adds.

require("ISUI/AdminPanel/ISServerSandboxOptionsUI")

local _originalApply = ISServerSandboxOptionsUI.onButtonApply

local function noDestroy() end

function ISServerSandboxOptionsUI:onButtonApply()
    local ownDestroy = rawget(self, "destroy")
    self.destroy = noDestroy

    local ok, err = pcall(_originalApply, self)

    self.destroy = ownDestroy
    if not ok then
        error(err)
    end

    -- integer/double options are parsed out of free-text entries, so the committed value
    -- can differ from what was typed; show what actually got applied.
    self:settingsToUI(self.options)
end
