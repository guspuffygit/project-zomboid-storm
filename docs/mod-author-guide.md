# Mod Author Guide

A Storm mod is a regular `.jar` that ships at least one class implementing
`io.pzstorm.storm.mod.ZomboidMod`. Storm scans every jar in a mod's
version-specific directory (e.g. `42/`), instantiates the entry point, and gives
it three hooks for registering surfaces:

```java
public final class MyMod implements ZomboidMod {

    @Override
    public void registerEventHandlers() {
        StormEventDispatcher.registerEventHandler(MyHandlers.class);   // static handlers
        StormEventDispatcher.registerEventHandler(new MyOtherHandler()); // instance handlers
    }

    @Override
    public List<Class<?>> getCommandClasses() {
        return List.of(MyServerCommand.class); // CommandBase subclasses
    }

    @Override
    public List<StormClassTransformer> getClassTransformers() {
        // Server-only bytecode patches. Gate on StormEnv.isStormServer() —
        // GameServer.server is false at collectTransformers() time and will
        // silently drop every transformer. Client-side patches are not allowed.
        if (!StormEnv.isStormServer()) {
            return List.of();
        }
        return List.of(new MyServerPatch());
    }
}
```

Logging conventions: `io.pzstorm.storm.logging.StormLogger.LOGGER` is the
preconfigured SLF4J logger every Storm component uses; mods are expected to
either grab it directly or attach their own `LoggerFactory.getLogger(...)`. Log
output is routed to `~/Zomboid/Logs/storm/main.log` and `storm/debug.log` per
the `-DLOG_LEVEL` flag.

## Annotation surfaces

Every handler class registered with `StormEventDispatcher` is scanned for the
following annotations. There is no per-method registration call — drop the
annotation on a method, register the class, and Storm wires it up.

| Annotation | Method signature | Fires when |
|------------|------------------|-------------|
| `@SubscribeEvent` | `(SomeZomboidEvent)` — exactly one `ZomboidEvent` parameter | The matching event is dispatched. Used for both Java-side events (`OnPacketReceivedEvent`, `OnLuaManagerInitEvent`, `OnBanUserEvent`, `OnItemTransferCompletedEvent`, `OnMainScreenRenderEvent`, …) and the ~190 Lua event bridges (`OnGameStartEvent`, `OnFillInventoryObjectContextMenuEvent`, etc.). |
| `@OnPacketReceived("PacketSimpleName")` | `(OnPacketReceivedEvent)` | A patched server packet's `processServer` runs. Filters by simple class name so handlers only fire for the packet they care about. Read protected fields via `event.getField("name")` (cached). |
| `@OnClientCommand` + a `ClientCommandEvent` subclass annotated `@ClientCommand(module="X", command="Y")` | `(MyTypedClientCommandEvent)` — single typed event parameter | The server receives `sendClientCommand("X", "Y", args)`. The event subclass extends `StormKahluaTable`, so handlers read args directly off `event.rawget("key")` instead of `KahluaTable` plumbing. |
| `@HttpEndpoint(path = "...", method = "GET" \| "POST")` | `(HttpRequestEvent)` or `(HttpRequestEvent, BodyT)` returning `void` | Storm's HTTP server receives the request. Paths are exact-match. With a body parameter, the dispatcher Jackson-decodes the body before invoking the handler and rejects malformed bodies with `400`. Requires `-Dstorm.http.port=<port>`. |

Handlers may be static (register the class) or instance (register an
instance) — Storm rejects a mix on the same handler.

## Typed packet events

In addition to the raw `OnPacketReceivedEvent`, every patched packet has a
typed subclass under `io.pzstorm.storm.event.packet.*` (e.g.
`ItemTransactionPacketEvent`, `EquipPacketEvent`, `NetTimedActionPacketEvent`,
`HitCharacterEvent`, `PlaySoundPacketEvent`, ~125 in total). Subscribe with
`@SubscribeEvent` on the typed class to get a strongly-typed `getPacket()`,
field-cache helpers, and a `capturePreState()` hook for snapshotting state
before the packet's `processServer` mutates it:

```java
@SubscribeEvent
public static void onTransfer(ItemTransactionPacketEvent event) {
    ItemTransactionPacket pkt = event.getPacket(); // typed, no reflection
    // Most packet fields are still protected; reach for them via
    //   event.getField("fieldName")  — cached after first read.
    Object state = event.getField("state");
}
```

## Lua API

Storm exposes a small `Storm` table plus a handful of utility libraries to
the dedicated server's Lua environment, and ships the same Lua files inside
its jar so connected players' game clients pick them up through the vanilla
mod-distribution flow. Symbols are safe to call from any Lua script after
`OnZomboidGlobalsLoad`.

| Symbol | Purpose |
|--------|---------|
| `Storm.isEnabled()` | `true` when Storm-shipped Lua loaded — handy as a feature-detect guard for vanilla-compatible mods. |
| `Storm.getVersion()` | Storm version string (matches `GET /storm/version`). |
| `Storm.debug(...)` | Forwards its arguments to Storm's debug logger. |
| `PersistedTable:save(file, tbl)` / `PersistedTable:read(file)` | Persist a flat `key=value` Lua table to a Zomboid-managed text file and read it back. Skips function / table values. Useful for per-player UI preference toggles read by Storm-shipped client Lua. |
| `StormBase64.encode(bytes [, start, end])` / `StormBase64.decode(str)` | Pure-Lua base64 codec used by Storm's screenshot pipeline; mods can reuse it for binary `sendClientCommand` payloads since vanilla Lua can't carry raw bytes through the network table. |

Any Lua files placed under `lua/` inside a mod jar are automatically loaded
into the server's Lua environment on `OnZomboidGlobalsLoad` and reach
connected players' game clients via PZ's standard mod-download flow, so a
Storm mod can ship its client / server / shared scripts inside the same jar
as its Java code.

## Built-in Server Commands

Storm registers a small set of debug commands in addition to vanilla's. They are
gated to `Capability.DebugConsole` and dispatched through PZ's normal command
pipeline, so they work from the server console, RCON, or any admin client.

| Command | Usage | Purpose |
|---------|-------|---------|
| `ping` | `/ping` | Responds with `pong`. Health-check the command pipeline end-to-end. |
| `printdebug` | `/printdebug events` \| `/printdebug sounds` | Dump the recorded triggered-event log or the `GameSounds` registry to `~/Zomboid/Logs/<date>_DebugLog-server.txt`. |
| `screenshot` | `/screenshot <username>` | Ask a connected client to render a screenshot and write it to its Lua cache dir as `storm_screenshot_<user>_<id>.png`. |

Mods register their own commands by adding a `CommandBase` subclass to
`StormCommandRegistry.MOD_COMMANDS`; Storm patches `CommandBase.findCommandCls`
and `CommandBase.getSubClasses` so mod commands resolve and show up in `/help`
alongside vanilla ones.
