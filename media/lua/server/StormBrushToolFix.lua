require("BuildingObjects/ISBrushToolTileCursor")

-- Storm Brush Tool Fix: vanilla ISBrushToolTileCursor:create() runs entirely on the
-- placing client (skipBuildAction bypasses the build action), and for generic tile
-- objects placeMoveableInternal never transmits to the server — only FloorTile and
-- WallOverlay have an isClient() transmit branch. The placed tile exists only in the
-- client's local chunk copy, so it vanishes as soon as the chunk reloads from the
-- server's authoritative state.
--
-- Fix: in multiplayer the client sends the placement to the server instead of placing
-- locally. The server re-runs the vanilla create() logic; placeMoveableInternal's
-- isServer() branches then transmit the object to every client (including the placer)
-- and the object lives in the server's chunk, so it persists in the chunk save.

local MODULE = "StormBrushTool"
local PLACE_TILE = "placeTile"

local _originalCreate = ISBrushToolTileCursor.create

function ISBrushToolTileCursor:create(x, y, z, north, sprite)
    if isClient() then
        sendClientCommand(self.character, MODULE, PLACE_TILE, {
            x = x,
            y = y,
            z = z,
            north = north,
            sprite = sprite,
        })
        return
    end
    _originalCreate(self, x, y, z, north, sprite)
end

local function onClientCommand(module, command, player, args)
    if module ~= MODULE or command ~= PLACE_TILE then
        return
    end
    if not player:getRole():hasCapability(Capability.UseBrushToolManager) then
        return
    end
    if
        type(args.x) ~= "number"
        or type(args.y) ~= "number"
        or type(args.z) ~= "number"
        or type(args.sprite) ~= "string"
    then
        return
    end
    local square = getCell():getGridSquare(args.x, args.y, args.z)
    if not square then
        return
    end
    -- self is unused by the vanilla create(); showDebugInfoInChat is a no-op on the
    -- server, so the original function is safe to run here as-is.
    _originalCreate(nil, args.x, args.y, args.z, args.north, args.sprite)
end

Events.OnClientCommand.Add(onClientCommand)
