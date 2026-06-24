# CLAUDE.md

Guidance for Claude Code working in this repo.

## HARD RULE: NO CLIENT-SIDE PATCHES

**No new Byte Buddy / `StormClassTransformer` patch may run on the client JVM. Not in a mod. Not in Storm itself. No exceptions.**

If a feature appears to need client-side bytecode interception, redesign it without — accept worse UX, drop the feature, or route through `sendClientCommand` / file transfers / extra server state instead. The rule is absolute because soft exceptions ("just this once, this feature is special") have caused real damage: compatibility breaks with vanilla clients, rendering glitches that took weeks to track down, accumulating bytecode-rewriting risk on every game update.

If you find yourself sketching architecture that needs a new client patch, **stop and tell the user**. Don't try to slip it in as a Storm-level helper. Don't argue the carve-out.

### What IS allowed on the client

- Lua under `media/lua/client/` (UI, rendering overlays, input, world inspection, file I/O).
- Lua event hooks (`Events.OnPostRender`, `Events.OnFillWorldObjectContextMenu`, `Events.OnReceiveGlobalModData`, etc.).
- Calling existing Java APIs reachable from Lua (`getTextManager()`, `IsoUtils.*`, `getCell()`, `Texture.*`).
- `sendClientCommand` / `sendServerCommand` for cross-process RPC.

### What is NOT allowed

- Any `StormClassTransformer` targeting a class that executes on the client JVM.
- `Advice.to(...)` on game methods that run on the client.
- Adding a transformer to `StormClassTransformers` without a server gate.

### Existing client-side patches (do not extend by analogy)

A small set already exists for historical reasons: `MainScreenStatePatch`, `UIWorldMapPatch`, `LuaEventManagerPatch`, `TISLogoStatePatch`, `PacketReceivedPatch`, `ChatManagerPatch`, `LuaExposerDumpPatch`, `LuaManagerPatch`, `DebugLogPatch`, `ThreadPatch`. **Do not propose new ones.** "It's the same kind of patch as X" is not a valid argument — existing entries are not precedent.

## Transformer gating (server-only patches)

Gate `ZomboidMod.getClassTransformers()` on `StormEnv.isStormServer()`, **not** `GameServer.server`. `GameServer.server` is `false` at `collectTransformers()` time and silently drops every patch. See `docs/mod-author-guide.md` for the full pattern.

## Source-only rule — never look in jars

Every Java class Storm and its consumer mods reference is available as `.java` source on disk. Always search `.java` files — **never** `unzip`, `jar -xf`, or read inside `.jar` files (including `~/.m2/`, Gradle caches, `build/zomboid-classpath/`). If a `find` for `.java` returns nothing, the query is wrong; fix the query.

`find` tip: use `-name '*.java'` for basename, `-path '*/storm/*'` for directory — don't combine them as `*storm*X*.java` (different path components, won't match).

## Commands you can't guess

| Task | Command |
|------|---------|
| Build + install Storm into the local workshop dir | `./gradlew clean spotlessApply installStorm` |
| Run local dedicated server | `./gradlew runProjectZomboidServer` |

`installStorm` fails with "Permission denied" on `agentlib.dll` / `storm.jar` while a client or dedicated server is running (the JVM memory-maps those files). Stop them first.

Versions and Steam Workshop IDs come from `gradle.properties`. Maven coordinates: `com.sentientsimulations:project-zomboid-storm:<pzVersion>_<stormVersion>`.

## Reference

- **Architecture (bootstrap chain, event system, mod loading, mod entry point)** — `docs/mod-author-guide.md`
- **JVM flags, sandbox options** — `docs/server-configuration.md`
- **What Storm patches in PZ (behavior, perf, bug fixes)** — `docs/what-storm-changes.md`
- **HTTP endpoints** — `docs/http-api.md`
- **Prometheus metrics (adding new ones)** — `docs/metrics.md`
- **Installation paths (Workshop, dedicated server, local dev)** — `docs/installation.md`

## Metadata

To disable metadata analytics, add `-DDISABLE_ANALYTICS=true`.
