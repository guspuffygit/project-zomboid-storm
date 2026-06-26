---
name: add-sandbox-option
description: Add a new Storm server-tunable knob exposed through PZ's vanilla sandbox-option system, with translation, runtime config, Prometheus gauge, and applier wiring. Use when introducing a new performance/behavior knob admins should set via the world setup UI or `<SaveName>.ini`.
---

# Add a Storm sandbox option

Each Storm sandbox option flows through five layers. Skipping any step leaves the option half-wired (unreadable in the UI, the gauge stays at its default, or admins can't discover the knob).

1. **Option declaration** in `media/sandbox-options.txt` — PZ reads this on world load and registers the option on `SandboxOptions.instance`. Storm options use `page = Storm` (buckets them on the "Storm | Performance" tab) and reference a translation key.
2. **Translation** in `media/lua/shared/Translate/EN/Sandbox.json` — `Sandbox_Storm_<Name>` label + `Sandbox_Storm_<Name>_tooltip`. B42's `Translator.tryFillMapFromFile` reads `Sandbox.json` only; the legacy B41 `Sandbox_EN.txt` Lua-table format is silently ignored.
3. **Runtime config class** (e.g. `StormZombieCullConfig`, `StormServerLosConfig`, `ServerLockFpsConfig`) — owns the current value (`AtomicInteger` / `volatile`), exposes a `getX()` reader called from the advice/hot path, and a `setX(int)` setter that clamps, stores, and pushes the new value to the Prometheus gauge. The setter is the **single mutation point**; sandbox apply and tests both funnel through it.
4. **Sandbox applier** — `io.pzstorm.storm.sandbox.StormPerformanceSandboxApplier` reads every Storm option at `OnServerStartedEvent` (server-only via the `GameServer.server` gate) and calls the matching config setter. If the option is missing or wrong-typed the applier logs a warn and leaves the controller at its compiled-in default.
5. **Documentation** in `docs/server-configuration.md` — the public-facing sandbox-options table (under `## Sandbox options (performance knobs)`) is the canonical admin reference. Every new option needs a row here AND its gauge name added to the trailing `storm_*` list, otherwise admins can't discover the knob. **Also update this file** (`.claude/skills/add-sandbox-option/SKILL.md`) — add or update the row in the "Current Storm sandbox options" table at the bottom so future skill invocations see the latest set.

## Step-by-step

### 1. Declare the option

`media/sandbox-options.txt`:

```
option Storm.MyKnob
{type = integer, min = 0, max = 1000, default = 100, page = Storm, translation = Storm_MyKnob,}
```

PZ supports `integer`, `boolean`, `double`, `string`, `enum`; almost every Storm option today is `integer`.

### 2. Add translation

`media/lua/shared/Translate/EN/Sandbox.json` (JSON, not the B41 Lua-table format):

```json
"Sandbox_Storm_MyKnob": "Short UI label",
"Sandbox_Storm_MyKnob_tooltip": "Longer explanation. Mention the vanilla default and what 0/extreme values do."
```

### 3. `DEFAULT_*` constant + setter on the config class

The setter clamps, stores, pushes to the gauge, returns the applied value:

```java
public static final int DEFAULT_MY_KNOB = 100;
private static final AtomicInteger VALUE =
        new AtomicInteger(clamp(Integer.getInteger("storm.myKnob", DEFAULT_MY_KNOB)));

public static int setMyKnob(int n) {
    int clamped = clamp(n);
    VALUE.set(clamped);
    StormPerformanceSandboxMetrics.setMyKnob(clamped);
    return clamped;
}
```

### 4. Prometheus gauge

Add a `storm_*` gauge to `StormPerformanceSandboxMetrics` with a setter. Initialise from the `DEFAULT_*` constant in the static block. Reading the gauge before any setter has run must return the vanilla default, not zero.

### 5. Wire the applier

`StormPerformanceSandboxApplier`:

```java
public static final String OPT_MY_KNOB = "Storm.MyKnob";

// inside onServerStarted:
applyMyKnob();

private static void applyMyKnob() {
    Integer value = readIntOption(OPT_MY_KNOB);
    if (value == null) return;
    MyKnobConfig.setMyKnob(value);
}
```

### 6. Document the option

Add a row to the sandbox-options table in `docs/server-configuration.md` (default, range, effect — match the existing style). Append the new gauge name to the trailing list of `storm_*` gauges in the same section so the "what gauges reflect this" prose stays complete. If the option drives a more involved subsystem (watchdog, retry logic, scheduler), also extend `docs/metrics.md`'s relevant feature subsection.

Then update this skill file — add/update a row in the "Current Storm sandbox options" table below so future invocations of the skill list the option among the live knobs.

## Verify

Set a non-default in the world's `<SaveName>.ini`, start the server, and hit `/metrics` to confirm the `storm_*` gauge reflects the new value.

## Current Storm sandbox options

| Option | Config class / setter | Effect |
|---|---|---|
| `Storm.ServerFps` | `ServerFpsConfig#applyUnifiedFps` | Server fps. Sets `tickIntervalMs = round(1000 / fps)`, `PerformanceSettings.getLockFPS()` on the server, and the `IsoPhysicsObject.update()` fps scalar. Vanilla 10. |
| `Storm.AnimalLOSTickInterval` | `AnimalLOSTickInterval#setTickInterval` | Per-animal stride for `IsoAnimal.updateLOS()`. 1 = vanilla every tick; 0 disables. |
| `Storm.ZombieCullThreshold` | `StormZombieCullConfig#setThreshold` | Storm cull threshold. 500 = vanilla cap; 0 disables culling. |
| `Storm.ServerLosThreads` | `StormServerLosConfig#setThreads` | Concurrent ServerLOS worker count (1–16). Pool always pre-allocates 15 helpers; this only controls how many receive work per tick. |
| `Storm.NetDataCapMs` | `MainLoopDrainCap#setCapMs` | Per-outer-loop-spin wall-clock cap (ms) on `GameServer.mainLoopDealWithNetData`. 0 disables; default 90. |
| `Storm.PeerSendBufferKickMb` | `PeerSendBufferKickConfig#setKickMb` | Per-peer HIGH send-buffer kick threshold (MB). 0 disables the watchdog. |
| `Storm.PeerSendBufferKickHoldTicks` | `PeerSendBufferKickConfig#setHoldTicks` | Consecutive server ticks the peer's HIGH send buffer must stay above the kick threshold before disconnect fires. |
| `Storm.ScreenshotPiecesPerPacket` | `StormScreenshotConfig#setPiecesPerPacket` | Base64 pieces (24573 bytes each) packed into a single `sendClientCommand` packet during a client `/screenshot` upload. Client-side read via `SandboxVars.Storm.ScreenshotPiecesPerPacket` in `StormScreenshot.lua`; this Java mirror exists only for the gauge. |
