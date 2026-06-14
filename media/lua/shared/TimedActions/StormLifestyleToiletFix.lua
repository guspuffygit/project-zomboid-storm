-- Fixes a bug in the Lifestyle workshop mod (3403870858) where players stay
-- stuck on the toilet after the LSUseToilet timed action ends.
--
-- LSUseToilet:perform() and :stop() never clear getModData().IsSittingOnSeat.
-- That flag is owned by an OnTick handler (LSIsSitActionHell, installed by
-- LSDoSitAction -> LSIsSitAction). While IsSittingOnSeat is true the handler
-- calls setBlockMovement(true) every tick and runs StopAllActionQueue() the
-- moment the character would turn -- which kills the walkAction + LSFlushToilet
-- that doFlush queues at the end of LSUseToilet. The only exit is
-- pressedMovement(true) from inside that handler, which does not fire
-- reliably, so the player is locked sitting.
--
-- The sibling LSUseTub already clears IsSittingOnSeat in its perform/stop;
-- LSUseToilet just forgot. We mirror that here. With the flag cleared,
-- LSIsSitActionHell's else branch runs on the next tick: restores the toilet
-- sprite, removes the seat-back tile object, sends animVar updates, calls
-- setBlockMovement(false), and unhooks itself from OnTick / OnPlayerMove /
-- OnMainMenuEnter.

local ok = pcall(require, "TimedActions/LSUseToilet")
if not ok or not _G.LSUseToilet then
    return
end

local function clearSittingState(character)
    if not character then
        return
    end
    local data = character:getModData()
    if not data then
        return
    end
    data.IsSittingOnSeat = false
    data.IsSittingOnSeatSouth = false
end

local originalPerform = LSUseToilet.perform
function LSUseToilet:perform()
    originalPerform(self)
    clearSittingState(self.character)
end

local originalStop = LSUseToilet.stop
function LSUseToilet:stop()
    originalStop(self)
    clearSittingState(self.character)
end
