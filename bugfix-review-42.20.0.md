# Storm bug-fix review — PZ 42.20.0

Reviewed 2026-07-29 against the refreshed decompile at `~/projects/project-zomboid-base`
(HEAD `1341749b` = 42.20.0; diff baseline `9eb0177c` = 42.19.1; intermediate `b1d711ca` = build 755,
which is a chmod-only commit for most files). One review agent per bug fix documented in
`docs/what-storm-changes.md` § "Bug fixes shipped with Storm"; every non-keep verdict was
manually re-verified against source before this report.

## Summary

| # | Bug fix | Patch | Verdict | Action |
|---|---------|-------|---------|--------|
| 1 | Cross-player action cancel | `ActionManagerPatch` | STILL_NEEDED | none |
| 2 | Cross-player transaction cancel | `ItemTransactionPacketPatch` | STILL_NEEDED | smoke test |
| 3 | Stale transaction cascade | `TransactionManagerPatch` | **NEEDS_REWORK** | **blocking** |
| 4 | UUID item transfers | `StormTransferHandler` + `StormTransferFix.lua` | **NEEDS_REWORK** | **blocking** (Lua side) |
| 5 | Zombie id collisions | `IsoObjectIDAllocateFixPatch` | PARTIALLY_FIXED | docs only |
| 6 | Zombie map invariant | `IsoZombieUpdateFixPatch` | STILL_NEEDED | none |
| 7 | Pop-cell save append | `RequestSaveCellSuppressPatch` | STILL_NEEDED | smoke test |
| 8 | Mid-handshake relevance leak | `UdpConnectionRelevancePatch` | STILL_NEEDED | none |
| 9 | `GeneralActionPacket.setReject()` | `GeneralActionPacketPatch` | STILL_NEEDED | none |
| 10 | `NetTimedActionPacket` accept/reject | `NetTimedActionPacketPatch` + `ActionStateContainerPatch` | STILL_NEEDED | javadoc fix |
| 11 | Null-animal save crash | `CompressIdenticalItemsPatch` | STILL_NEEDED | none |
| 12 | Null-`adef` animal guards (×5) | `IsoAnimal*`/`IsoMovingObject*`/`BaseVehicleSavePatch` | STILL_NEEDED | none |
| 13 | Idempotent `SpriteConfig.onAddedToOwner` | `SpriteConfigFixPatch` | STILL_NEEDED | none |
| 14 | Case-insensitive whisper | `ChatServerProcessWhisperPatch` | STILL_NEEDED | docs fix |
| 15 | Workshop download recovery | `GameServerWorkshopItemsPatch` + recovery/probe | STILL_NEEDED | none |
| 16 | Translator spam short-circuit | `TranslatorPatch` | STILL_NEEDED | none |

Nothing was fixed upstream to the point of being removable. Two units are broken by the 42.20.0
**multi-item transaction refactor** and must be reworked before running Storm 2.4.0 on 42.20.0.
A third breakage was found outside the bug-fix list (zombie cull, below).

## Blocking rework

### 1. `TransactionManagerPatch` — stale-sweep is dead, logs an error every tick

42.20.0 refactored `zombie.core.Transaction` from single-item to multi-item batches:
the top-level `ContainerID sourceId`/`destinationId` fields (42.19.1 `Transaction.java:42,44`)
were moved into a new `Transaction$TransactionEntry` inner class held in an
`entries` list (`Transaction.java:40,457`). The patch's `initFieldHandles()`
(`TransactionManagerPatch.java:68-71`) does `getDeclaredField("sourceId")` on `Transaction`,
which now throws `NoSuchFieldException` on every call — the stale sweep never runs and the
catch block emits one `LOGGER.error` per server tick.

The vanilla bug is NOT fixed: `TransactionManager.isConsistent` (`:182-193`) still rejects on
`entry.sourceId.getContainer() == null` per entry, so one stale in-flight transaction still
poisons all new transfers until its `endTime` lapses.

Rework: keep the transformer, matcher (`update()`, 0-arg — still intact), and the
`StormTransferHandler.processPending()` call (Storm's transfer tick pump — deleting the patch
would stall Storm transfers). Rewrite the reflection to read `Transaction.entries` and the
`sourceId`/`destinationId` fields of `zombie.core.Transaction$TransactionEntry`; a transaction
is stale when `state == Accept` and any entry's source container (or defined-type destination
container) resolves null. `ContainerID.getContainer()`/`getContainerType()`/`ContainerType.Undefined`
are unchanged.

### 2. `StormTransferFix.lua` — client `transferItem` is no longer a no-op → item loss risk

The server-side `StormTransferHandler.java` is fully intact (all called vanilla methods verified;
the byte-ID wraparound at `Transaction.java:34,110-114` and the ID-0 vacuous truth at
`TransactionManager.java:410-441`/`LuaManager.java:10029-10037` persist verbatim, so the UUID
replacement stays justified). The Lua driver is what broke:

- 42.19.1's `ISInventoryTransferAction:transferItem` began with an `isClient()` early-return.
  42.20.0 removed it and moved the guard to the call site:
  `if not isClient() then self:transferItem(item) end` (`ISInventoryTransferAction.lua:582-583`).
- Storm's `perform()` override (`StormTransferFix.lua:453-454`) still calls
  `self:transferItem(item)` unconditionally, with a comment claiming it no-ops on the client.
  On 42.20.0 it performs a real local move (`ISTransferAction.lua:108-134`), including
  `transmitRemoveItemFromSquare` to the server for floor pickups → server-side item deletion
  and phantom container moves for items the Storm server never authorized.
- Batching moved client-side: `checkQueueList`'s up-to-20-light-item merge lost its
  `not isClient()` gate (42.19.1 `:850` vs 42.20.0 `:856`), and `start()` now calls
  `createItemTransaction(character, items, src, dest)` with an item list plus a new
  `waitToStart()` delay. Storm's re-arm only authorizes `items[1]` per UUID
  (`StormTransferFix.lua:475-476`), so batch members 2..N are exactly the unauthorized ones
  hitting the real `transferItem`.

Rework: (a) mirror vanilla's split — replace the unconditional `self:transferItem(item)` with the
sound-only client path; (b) handle client-side batching: either one Storm UUID transaction per
queued item, or extend the StormTransfer protocol to carry an item-id list per UUID; (c) re-sync
the replicated `start()` with the 42.20.0 body; (d) minor Java parity: add
`transmitCompleteItemToClients()` in `StormTransferHandler.spawnPairedRadioObject`
(`:561-573`) to match TIS's new radio sync fix.

### 3. (Out of scope but confirmed) `ZombieCullThresholdPatch` / `ZombieCullDisablePatch` silently no-op — RESOLVED, patches deleted

Not in the bug-fix list (behavioral override), surfaced during the zombie-map-invariant review and
verified directly: 42.20.0 rewrote `zombie.popman.ZombieCountOptimiser`, deleting `startCount()`
and `incrementZombie(IsoZombie)` (replaced by `prepareZombiesForDeletion()` +
`canBeDeletedUnnoticed(zombie, connection)`, with cull candidates now resolved through
`zombieMap.get` via `NetworkZombiePacker.zombiesToSend`). Both Storm cull patches matched the old
method names → both silently failed to apply.

Follow-up investigation found vanilla fixed every problem the patches existed for, so they were
removed rather than reworked:

| | 42.19.1 (what Storm patched) | 42.20.0 |
|---|---|---|
| Cull budget | `max(0, entire-map zombieList.size() − N)` | `max(0, zombies streamed to *this connection* − N)` |
| Budget decrement | missing → over-culls ~10%/frame | `zombiesCountForDelete--` present |
| Option range | min 10, max 500, default 300 | min 0, max 5000, default 300 |
| Disable culling | impossible (min 10) | `if (zombiesCountBeforeDeletion > 0)` → 0 = off |
| "Unnoticed" radius | `(range−2)*10 / 2` | `(range−2)*10` (doubled) |

The whole-map cap was the reason a 500-zombie limit starved spawning across the entire world;
it is now a per-connection streaming threshold.

`Storm.ZombieCullThreshold` was removed outright rather than kept as a write-through override.
The override re-applied on every admin sandbox push, so a stale carried-over value would silently
stomp an admin's direct edit of `ZombieConfig.ZombiesCountBeforeDelete` — a footgun that did not
exist while the patches consumed the value in bytecode. `StormZombieCullConfig` is now a read-only
reporter feeding `storm_zombie_cull_threshold` and startup analytics. Servers that had set `0` to
disable culling revert to vanilla's per-connection default of 300 and must set
`ZombieConfig.ZombiesCountBeforeDelete = 0` to keep it off; no in-game migration warning is
possible, since `SandboxOptions` only iterates *registered* options on load, so an orphaned key is
never read and is dropped on the next save. Upgrade note lives in `docs/server-configuration.md`.

## Partially fixed upstream

### 5. Zombie id collisions (`IsoObjectIDAllocateFixPatch`) — keep as-is

`zombie.network.IsoObjectID` is byte-identical to 42.19.1 (mode-only diff): `allocateID()` is
still a free-running 16-bit counter and `put()` still blind-overwrites — the zombie-mitosis half
(`ServerMap.getUniqueZombieId`, `ServerMap.java:90-91`) is fully still needed and the patch
applies cleanly. What changed: vanilla added a caller-level retry loop **for animals only** —
`AnimalInstanceManager.allocateID()` (`:27-36`) probes `AnimalMap.allocateID()` up to 32767
attempts, then throws `IllegalStateException`. With Storm's class-level fix active the wrapper
exits on its first iteration (benign, no double-apply); on pool exhaustion Storm's `-1` return
bypasses vanilla's new throw (animal spawns unregistered with onlineID -1 — same pre-update Storm
behavior). Action: documentation note in the patch javadoc + `docs/what-storm-changes.md` only.

## Keep as-is — notes worth recording

- **#1 action-cancel / #9 gap-setreject**: every involved file (ActionManager, Action,
  PlayerID/IDShort, IDescriptor, AnimEventEmulator, all packet call sites) is chmod-only over
  the update; all reflective handles verified. The two patches are interdependent (StopAdvice
  needs setReject's playerId) — keep both.
- **#2 item-transaction-cancel**: bug persists (`ItemTransactionPacket.processServer` Reject
  branch still removes by byte id alone), and the patch survives the multi-entry refactor because
  it only reflects `Transaction.id/state/playerId`, which are unchanged. Since the class was
  genuinely refactored around it, run the two-player cancel smoke test on 42.20.0. Note: one
  transaction now bundles multiple items, so one cancel covers the whole batch — filter semantics
  unaffected.
- **#6 zombie-map-invariant**: bug intact (`updateInternal` allocates onlineId with no
  `zombieMap.put`, `IsoZombie.java:3360-3361`; `load()` drops zombies in with onlineId=-1). The
  big IsoZombie diff is the networkAi→`NetworkZombieComponent` ECS refactor, which doesn't touch
  the patched region. Bonus: 42.20.0's rewritten cull selection resolves candidates through
  `zombieMap`, so the invariant now also keeps culling able to see chunk-loaded zombies.
- **#7 zpop-save-append**: `requestSaveCell` and its `IsoChunk.removeFromWorld` call site are
  byte-identical; the `zpop_virtual.bin` autosave path is unchanged. Vanilla added a *spawn-side*
  mitigation (`clearChunkForReplace`/`zedClearedChunks` — removes real zombies from a chunk before
  native-emitted spawns), which is complementary and does not stop the on-disk append growth.
  Native `n_saveCell` semantics can't be source-verified — worth a quick live check that zombies
  persist and no clones spawn.
- **#8 udp-relevance**: all four relevance methods + `isFullyConnected()` intact; no vanilla
  fullyConnected filtering added. `CONNECTION_READY_INTERVAL` went 5 s → 15 s, so handshakes are
  tolerated longer — the guard is more valuable, not less.
- **#10 nta-accept-reject**: target files byte-identical; patch correct. 42.20.0 demoted
  vanilla's `tryInsertChildState` "state not supported by parent" logs from warn to trace, so
  `ActionStateContainerPatch` is now the only visibility into that race at default log levels.
  Cleanup: `NetTimedActionPacketPatch` javadoc advertises a `storm.fix.nettimedaction.players`
  selective-rollout sysprop that the code never reads — fix the doc or implement it.
- **#11 compress-null-animal**: `AnimalInventoryItem.save()` still NPEs on null animal. Vanilla
  added a null guard only to the 2-arg `save(ByteBuffer, InventoryItem)` overload and for a
  different bug (null item reference); the patch's `takesArguments(3)` matcher excludes that
  overload — no conflict.
- **#12 null-adef-guards**: all 5 targets intact with identical signatures; vanilla constructors
  still leave `adef` null with only a debug log. Adjacent churn (animal sound-response refactor,
  new `getNetworkSpeedMul()` inserted directly above `reattachBackToMom`, zombie ECS refactor)
  touches none of the matched methods.
- **#13 spriteconfig-idempotent**: only change is the world-version bump (247→249) inside
  `loadSyncData`; `GameEntity.receiveSyncEntity` still unconditionally re-inits.
- **#14 whisper-case**: bug fully intact (server + client `.equals` lookups). Docs bug:
  `docs/what-storm-changes.md` names the target `ChatServer.processWhisperMessage`, which doesn't
  exist — the patched method is `processPlayerStartWhisperChatPacket`.
- **#15 workshop-recovery**: install flow untouched (`GameServerWorkshopItems.java:80` still
  passes nullable `GetItemInstallFolder` into unguarded `deleteDirectory`,
  `ZomboidFileSystem.java:1277`); recovery/probe assumptions all hold.
- **#16 translator-spam**: the advice's 24 hard-coded prefixes still exactly match
  `getTextInternal`'s dispatch — none added or removed in 42.20.0. Standing risk to re-check
  every update: a new vanilla prefix would make the patch silently return valid keys
  untranslated.

## Action checklist

1. **[done 2026-07-29]** Reworked `TransactionManagerPatch` reflection for
   `Transaction$TransactionEntry` (transformer + `processPending()` pump kept; per-entry sweep).
2. **[done 2026-07-29]** Reworked `StormTransferFix.lua` for the 42.20.0 client-batched transfer
   protocol (client never calls `transferItem`; every batch member gets its own UUID
   authorization at start and re-arm).
3. **[done 2026-07-29]** Deleted `ZombieCullThresholdPatch`/`ZombieCullDisablePatch` and their three
   advice classes — 42.20.0's rewritten `ZombieCountOptimiser` fixes all three problems they
   existed for. `Storm.ZombieCullThreshold` was removed too (option declaration, both translation
   keys, applier write-through); `StormZombieCullConfig` is now a read-only reporter for the
   `storm_zombie_cull_threshold` gauge. Operators set vanilla's
   `ZombieConfig.ZombiesCountBeforeDelete` directly.
4. `StormTransferHandler.spawnPairedRadioObject`: add `transmitCompleteItemToClients()`.
5. Docs: fix whisper method name; note animal-only vanilla ID retry loop; note batch-cancel
   semantics; note spawn-side zpop mitigation; fix/implement `storm.fix.nettimedaction.players`.
6. Smoke tests on 42.20.0: two-player transaction cancel; zpop clone/persistence check.

Full per-review evidence (file:line for every claim): workflow output at
`/tmp/claude-1000/-mnt-wsl-PHYSICALDRIVE3p2-extraspace-projects-project-zomboid-storm/5763cc8d-be8d-4e3b-aef0-d164ab8b25f0/tasks/wp12dc0bl.output`.
