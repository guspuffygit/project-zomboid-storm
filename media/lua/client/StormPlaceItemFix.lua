-- Makes "Place Item" server-authoritative in multiplayer.
--
-- Vanilla ISDropWorldItemAction:complete() builds the IsoWorldInventoryObject with
-- AddWorldInventoryItem(item, x, y, z, false) -- transmit=false -- and only tells the
-- server to delete the item from the player's inventory. The placed item therefore
-- exists on the placing client and nowhere else: other players never see it, it is gone
-- after a relog, and it cannot be picked up again, because every pickup path resolves the
-- ground item on the server. There is no vanilla client-to-server route:
-- transmitCompleteItemToServer() and AddItemToMapPacket.processServer were removed, so
-- a client-built ground object cannot reach the server.
--
-- Instead of touching the world locally, send the placement to StormTransferHandler and let
-- the server move the item and broadcast AddItemToMap back to every relevant client -- the
-- placer included. This mirrors how Storm already handles floor drops.

require("TimedActions/ISDropWorldItemAction")

local MODULE = "StormTransfer"

local _originalComplete = ISDropWorldItemAction.complete

-- Swaps a lit candle/lantern for its unlit counterpart, as vanilla complete() does before
-- placing. Uses the vanilla sync helpers, so the swap is already reflected server-side.
local function swapLitItem(self, litType, unlitType)
    if self.item:getType() ~= litType then
        return
    end

    local replacement = instanceItem(unlitType)
    self.character:getInventory():AddItem(replacement)

    replacement:setUsedDelta(self.item:getCurrentUsesFloat())
    replacement:setCondition(self.item:getCondition())
    replacement:setFavorite(self.item:isFavorite())

    sendAddItemToContainer(self.character:getInventory(), replacement)

    self.character:getInventory():Remove(self.item)
    sendRemoveItemFromContainer(self.character:getInventory(), self.item)

    self.item = replacement
end

function ISDropWorldItemAction:complete()
    if not isClient() then
        return _originalComplete(self)
    end

    swapLitItem(self, "CandleLit", "Base.Candle")
    swapLitItem(self, "Lantern_HurricaneLit", "Base.Lantern_Hurricane")

    sendClientCommand(self.character, MODULE, "placeItem", {
        itemId = self.item:getID(),
        x = self.sq:getX(),
        y = self.sq:getY(),
        z = self.sq:getZ(),
        xoffset = self.xoffset,
        yoffset = self.yoffset,
        zoffset = self.zoffset,
        rotation = self.rotation,
    })

    return true
end
