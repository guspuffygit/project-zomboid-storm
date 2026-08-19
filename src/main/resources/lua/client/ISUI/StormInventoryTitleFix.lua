-- Injected into the client Lua env by StormEventHandler on OnZomboidGlobalsLoadEvent, i.e.
-- immediately after every LuaManager.LoadDirBase() (boot, Core.ResetLua, IngameState.exit), so
-- it re-applies over a freshly reloaded ISInventoryPage and over any mod that patched it first.
-- Shipping it in storm.jar instead of media/lua/client keeps it out of the server's client-file
-- checksum, so a client can carry it against a server that does not have it.
--
-- Storm Inventory Title Fix: the container title in the inventory/loot title bar is
-- right-aligned against a hardcoded reservation of MeasureStringX("9999.99 / 9999") + 30.
-- That reservation ignores the "(items / limit)" suffix the MP branch appends when
-- ItemNumbersLimitPerContainer is set, so on a server with an item limit the weight label
-- is far wider than the space reserved for it and the title draws on top of it
-- (e.g. "Roofrack" over "21.93 / 42 (13 / 800)").
--
-- Both labels stay where vanilla puts them; the title is re-anchored against the weight
-- label's measured left edge and ellipsized when the title bar is too narrow to hold it.

if not ISInventoryPage then
    return
end

-- kept on the class so a reload of this file re-wraps vanilla instead of stacking wrappers
ISInventoryPage.stormOriginalPrerender = ISInventoryPage.stormOriginalPrerender
    or ISInventoryPage.prerender
ISInventoryPage.stormOriginalDrawTextRight = ISInventoryPage.stormOriginalDrawTextRight
    or ISInventoryPage.drawTextRight
ISInventoryPage.stormOriginalDrawText = ISInventoryPage.stormOriginalDrawText
    or ISInventoryPage.drawText

local ORIGINAL_PRERENDER = ISInventoryPage.stormOriginalPrerender
local ORIGINAL_DRAW_TEXT_RIGHT = ISInventoryPage.stormOriginalDrawTextRight
local ORIGINAL_DRAW_TEXT = ISInventoryPage.stormOriginalDrawText

local TITLE_GAP = 10
local ELLIPSIS = "..."

local function measure(font, str)
    return getTextManager():MeasureStringX(font or UIFont.Small, str)
end

local function ellipsize(page, str, font, available)
    if available <= 0 then
        return nil
    end
    local cache = page.stormTitleFit
    if cache and cache.str == str and cache.available == available and cache.font == font then
        return cache.result
    end

    local result = str
    if measure(font, str) > available then
        result = nil
        local length = #str
        while length > 0 do
            length = length - 1
            local candidate = string.sub(str, 1, length) .. ELLIPSIS
            if measure(font, candidate) <= available then
                result = candidate
                break
            end
        end
    end

    page.stormTitleFit = { str = str, available = available, font = font, result = result }
    return result
end

local function isTitleText(page, str)
    return page.title ~= nil and page.title ~= "" and string.sub(str, 1, #page.title) == page.title
end

local function contentLeftEdge(page)
    if page.infoButton then
        return page.infoButton:getRight() + 6
    end
    return 6
end

function ISInventoryPage:prerender()
    if self.stormWeightLeft ~= nil then
        self.stormWeightLeftLastFrame = self.stormWeightLeft
    end
    self.stormWeightLeft = nil
    ORIGINAL_PRERENDER(self)
end

-- Right-aligned title (world containers). Every other right-aligned label in the title bar
-- (the weight/capacity readout) contributes its left edge; the title is held clear of it.
function ISInventoryPage:drawTextRight(str, x, y, r, g, b, a, font)
    if str == nil then
        return ORIGINAL_DRAW_TEXT_RIGHT(self, str, x, y, r, g, b, a, font)
    end

    if self.onCharacter or not isTitleText(self, str) then
        local left = x - measure(font, str)
        if self.stormWeightLeft == nil or left < self.stormWeightLeft then
            self.stormWeightLeft = left
        end
        return ORIGINAL_DRAW_TEXT_RIGHT(self, str, x, y, r, g, b, a, font)
    end

    if self.stormWeightLeft == nil then
        return ORIGINAL_DRAW_TEXT_RIGHT(self, str, x, y, r, g, b, a, font)
    end

    local maxRight = self.stormWeightLeft - TITLE_GAP
    if x > maxRight then
        x = maxRight
    end
    local fitted = ellipsize(self, str, font, x - contentLeftEdge(self))
    if fitted == nil then
        return
    end
    return ORIGINAL_DRAW_TEXT_RIGHT(self, fitted, x, y, r, g, b, a, font)
end

-- Left-aligned title (player inventory). This one is drawn before the weight label, so
-- it clamps against the previous frame's edge.
function ISInventoryPage:drawText(str, x, y, r, g, b, a, font)
    if str == nil or not self.onCharacter or not isTitleText(self, str) then
        return ORIGINAL_DRAW_TEXT(self, str, x, y, r, g, b, a, font)
    end

    local weightLeft = self.stormWeightLeftLastFrame
    if weightLeft == nil then
        return ORIGINAL_DRAW_TEXT(self, str, x, y, r, g, b, a, font)
    end

    local fitted = ellipsize(self, str, font, weightLeft - TITLE_GAP - x)
    if fitted == nil then
        return
    end
    return ORIGINAL_DRAW_TEXT(self, fitted, x, y, r, g, b, a, font)
end
