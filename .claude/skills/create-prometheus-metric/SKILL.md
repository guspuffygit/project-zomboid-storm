---
name: create-prometheus-metric
description: >-
  Constraints, idioms, and gotchas when adding a new Prometheus metric in Storm
  or a Storm mod — instrument type selection, naming, label cardinality,
  pre-resolved DataPoints, `StormPrometheus.registry()` registration, callback
  discipline, and the server-only gating pattern that connects an advice call
  site to its metric class. Use when writing a new class under
  `io.pzstorm.storm.metrics/` (or the equivalent in a consumer mod), adding
  `Counter` / `Histogram` / `Gauge` / `GaugeWithCallback` / `CounterWithCallback`
  instruments, wiring a `recordNanos(long)` into an existing advice, or
  debugging "metric never shows up in `/metrics`". Also consult
  `docs/metrics.md` for the exposition/scrape story and the current metric
  reference — this skill is about the code shape.
---

# Adding Prometheus metrics in Storm

This is the code-shape half of Storm's metrics setup. `docs/metrics.md` covers
the runtime (`-DprometheusPort`, PZ's HTTP server, native-histogram scrape
config) and lists what's already exposed; **read that first** if the question
is "how do I turn metrics on" or "what's already there". This skill is about
what to actually type when adding a new metric class.

Everything below is drawn from the ~60 existing classes under
`src/main/java/io/pzstorm/storm/metrics/`. If a call site doesn't match one of
these shapes, prefer copying the closest existing class over inventing a new
shape.

Hard prerequisites, both from `CLAUDE.md`:

- **Storm-side patches must be server-only.** Metric recording from an advice
  body has to gate on `GameServer.server` at runtime, *and* the corresponding
  transformer registration in `StormClassTransformers` has to be inside a
  `if (StormEnv.isStormServer())` block. See `storm-bytebuddy-patches` skill
  for the full patch-registration story — this skill assumes you got that
  right.
- **`storm_*` for Storm framework internals, `pz_*` for PZ-code timing,
  `<modid>_*` for consumer-mod metrics.** Don't mix.

---

## 1. Pick the instrument type first, before typing anything

Wrong choice here is expensive: renaming a metric later means downstream
Prometheus rules and Grafana panels re-key. Decide from the recording pattern,
not the intuition:

| Pattern at the call site                                              | Instrument                          |
|-----------------------------------------------------------------------|-------------------------------------|
| "Every time X happens, count it."                                     | `Counter`                           |
| "Time how long each call to X takes."                                 | `Histogram` (`.nativeOnly()`)       |
| "How many of Y currently exist" — pushed from a setter you control.   | `Gauge`                             |
| "How many of Y currently exist" — pulled from a getter at scrape.     | `GaugeWithCallback`                 |
| "Cumulative allocation / IO bytes per thread etc., read from JVM."    | `CounterWithCallback`               |
| "Distribution but I can't guarantee the scraper accepts native."       | `Histogram` w/ `.classicUpperBounds(double[])` |

Rules for choosing between the two histogram variants: reach for
`.nativeOnly()` **unless** you know the scrape target rejects native
histograms — the bundled `client_java` supports them, and every existing
Storm `_duration_seconds` uses `.nativeOnly()`. If you're forced onto classic
buckets, pick boundaries that bracket the observed range in production; a
misplaced bucket set is worse than not having the histogram.

Rules for choosing between `Gauge` and `GaugeWithCallback`: if the source of
truth is a live field/collection you can `size()`/`get()` cheaply, use
`GaugeWithCallback` and skip the sync work of pushing on every change. If the
value is discrete and rarely updated (sandbox knobs, tick interval), use
`Gauge` with `.set(...)` from the setter — see `StormPerformanceSandboxMetrics`.

Rules for `Counter` vs `CounterWithCallback`: `CounterWithCallback` is only
worth reaching for when the accumulator already exists outside your code
(JVM MXBeans, OS counters). If you'd have to run a background daemon to feed
a counter, use a plain `Counter` and increment it from the code path that
knows about the event.

---

## 2. Class skeleton — copy this exactly

```java
package io.pzstorm.storm.metrics; // or your mod's io.pzstorm.<mod>.metrics

import io.prometheus.metrics.core.metrics.Histogram;

public final class ChunkLoadMetrics {

    private static final Histogram CALL_DURATION =
            Histogram.builder()
                    .name("pz_chunk_load_call_duration_seconds")
                    .help("Duration of ChunkLoad advice invocations.")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private ChunkLoadMetrics() {}

    public static void recordNanos(long nanos) {
        CALL_DURATION.observe(nanos / 1e9);
    }
}
```

Non-negotiables in that skeleton:

1. `public final class …Metrics` — the class is a static holder, never
   instantiated. All existing `metrics/` classes are `final`.
2. **Private no-arg constructor.** Blocks accidental `new`.
3. **Instruments as `private static final`.** Registers at class load; that's
   how the shared `PrometheusRegistry.defaultRegistry` picks it up.
4. **Register via `StormPrometheus.registry()`, not
   `PrometheusRegistry.defaultRegistry` directly.** The helper documents intent
   and gives you one place to change if the shared-registry story ever moves.
5. **One public `record…` method per observation type.** Signature converts to
   the base unit at the boundary: `nanos / 1e9`, `millis / 1e3`, `bytes / (1024.0 * 1024.0)`,
   never the other way around. Metrics that need labels take them as method
   parameters — the label lookup lives inside the metric class, not the
   caller.
6. **Nothing runs in a `static {}` block except deliberate cross-class
   ensureStarted() calls** (see §6 below on lazy class loading). No IO, no
   allocation-heavy setup. If you need conditional init (e.g., MXBean
   probing), gate it inside a private helper method called from the field
   initializer — see `ThreadAllocBytesMetrics.initBean()`.

---

## 3. Naming — get it right the first time

Verbatim from what's currently in the tree and from `docs/metrics.md`:

- **snake_case** end-to-end. Prometheus convention.
- **Prefix by ownership**:
  - `pz_*` — times/counts PZ game code (advice around vanilla methods)
  - `storm_*` — Storm framework internals (event dispatch, LOS engine,
    transfer handler, HTTP endpoint, connection watchdog)
  - `<modid>_*` — anything shipped in a consumer mod
- **`_total` on every counter.** Prometheus exposition format requires it and
  PromQL functions assume it.
- **Base units in the name.** `_seconds`, `_bytes`, `_ms` (only when the value
  is bounded and always ms — vanilla PZ ping is the only current `_ms`
  example). Never `_microseconds` / `_kilobytes` — convert at the call site.
- **No high-cardinality labels.** Numeric IDs, chunk coords, usernames on a
  server with thousands of unique logins over time — all forbidden. Cap at
  single-digit cardinality where possible.
  - When a label needs to be free-form for debugging (e.g., `path` on
    `storm_http_requests_total`), bucket unrecognised values under `"unknown"`
    so cardinality stays bounded (`HttpEndpointDispatcher` does exactly this).
  - Peer usernames (`storm_peer_*`) are an accepted exception because
    cardinality is capped at concurrent player count and disconnected peers
    are zeroed out — see `StormConnectionMetrics`.

Naming pattern for pairs. When the metric measures one advice, the composite
is a `Histogram` for latency + a `Counter` for scheduler ticks so PromQL can
divide:

- `<area>_call_duration_seconds` (Histogram)
- `<area>_ticks_total` (Counter, incremented once per scheduler frame by
  `MovingObjectUpdateSchedulerStartFrameAdvice`)

That's 21 pairs in the current tree — see `docs/metrics.md` §"Standard
composite" for the list.

---

## 4. Labels: pick a strategy for label-value lookup

The Prometheus client library resolves `labelValues("a","b")` to a
`DataPoint` on every call — that lookup allocates an `Arrays.asList(...)`
per invocation. For anything that fires in the hot path, pre-resolve.

### 4a. Static label set — pre-resolve to fields (the fast path)

Use this when the set of label combinations is **known at class-load time**
(enum, small string list, boolean-like flag). Every hot-path Storm metric with
labels does this.

```java
private static final Counter POOL_OPS =
        Counter.builder()
                .name("pz_bit_header_pool_ops_total")
                .help("BitHeader pool operations by size and op.")
                .labelNames("size", "op")
                .register(StormPrometheus.registry());

private static final CounterDataPoint GET_BYTE    = POOL_OPS.labelValues("byte",    "get");
private static final CounterDataPoint GET_SHORT   = POOL_OPS.labelValues("short",   "get");
// ... one per combination
private static final CounterDataPoint RELEASE_LONG = POOL_OPS.labelValues("long",   "release");

public static void observeReleaseLong() {
    RELEASE_LONG.inc();
}
```

That's `BitHeaderMetrics`. `TransferMetrics` and the four-outcome
`Counter` are the same shape (`outcome={accepted,rejected,done,cancelled}`).
Do not skip pre-resolution just because "it's only 4 combinations" — hot
paths are hot, and this is a one-line change.

### 4b. Dynamic label set — `ConcurrentHashMap` cache with `computeIfAbsent`

Use this when the label values are dynamic strings that only surface at
runtime (event class name, packet class name, HTTP path). The label set is
still bounded — you need to be sure of that before choosing this pattern.

```java
private static final Counter DISPATCHES =
        Counter.builder()
                .name("storm_event_dispatch_total")
                .help("Event dispatches that found matching handlers.")
                .labelNames("event")
                .register(StormPrometheus.registry());

private static final Map<String, CounterDataPoint> DISPATCH_DP = new ConcurrentHashMap<>();

public static void recordDispatch(String event) {
    DISPATCH_DP.computeIfAbsent(event, e -> DISPATCHES.labelValues(e)).inc();
}
```

Same shape for `Histogram`: cache `DistributionDataPoint`. See
`EventDispatchMetrics` (labels: `event`) and `PacketDispatchMetrics` (labels:
`packet`; and for the multi-label `TYPED_EVENT`, use a compound key like
`packet + ":" + result` so one map covers both label positions).

### 4c. Low-frequency or callback-driven — `labelValues(...)` at the call site is fine

`StormConnectionMetrics.recordAll()` runs once per server tick and iterates
peers — it calls `SEND_BUFFER_BYTES.labelValues(username, "high").set(...)`
directly. That's ~10 calls per tick per connected player; no need to cache.

`HttpEndpointMetrics.recordRequest(method, path, status)` calls
`REQUESTS.labelValues(method, path, Integer.toString(status)).inc()` on each
HTTP request — HTTP request rate is low enough that the per-call allocation
is invisible.

---

## 5. Wiring the recorder into an advice (or any other call site)

For the standard `pz_*_call_duration_seconds` shape, the advice is:

```java
public class BaseVehicleUpdateAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
        }
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (!GameServer.server) {
            return;
        }
        if (startNanos == 0L) {
            return;
        }
        BaseVehicleUpdateMetrics.recordNanos(System.nanoTime() - startNanos);
    }
}
```

Points that trip people up:

- **`GameServer.server` guard at *runtime* in the advice body**, even though
  the patch is registered under `StormEnv.isStormServer()`. Belt-and-suspenders:
  registration gating prevents client bytecode rewriting; the runtime guard
  covers cases where a "server" JVM briefly runs the target method during
  boot before `GameServer.server` flips.
- **`onThrowable = Throwable.class`** on the exit advice — otherwise a thrown
  exception skips recording and the histogram undercounts. Every duration
  advice in the tree does this.
- **`@Advice.Enter long` for the timestamp, not `@Advice.Local`.** The two
  styles both exist historically (`IsoChunkLoadAdvice` uses
  `@Advice.Local("startNanos")`, `BaseVehicleUpdateAdvice` uses the
  return-value form), but the return-value form is the current convention —
  fewer surprises around `@Advice.Local` initialization order.
- **`startNanos == 0L` sentinel check.** When `GameServer.server` is
  false at enter, the enter advice returns `0L`, and the exit advice must
  bail on that. Do not use `System.nanoTime()` as a sentinel — it can be
  legitimately zero on some platforms.
- **Preserve the `recordNanos(long)` signature across future changes.** Byte
  Buddy advice inlines the call; if you rename or change the parameter
  list, every patched class needs a rebuild. Adding a new `recordXxx(...)`
  method is cheap; renaming an existing one is expensive.

If the same advice also feeds `MainLoopStepTimings.record("Label", elapsed)`
(the per-tick timings-log system, gated on `-Dstorm.mainloop.timings=true`),
call both from the same exit block — they consume the same `elapsed` value.
See `IsoChunkLoadAdvice` for the joint pattern.

---

## 6. Callback discipline (`GaugeWithCallback` / `CounterWithCallback`)

Callbacks fire on **every scrape**. Prometheus is happy scraping every 15
seconds; a 100 ms callback burns 0.7% of CPU just serving scrapes.

- **Cache what you can outside the callback.** `ThreadAllocBytesMetrics`
  looks up thread names before the (slightly pricier) per-thread
  `getThreadAllocatedBytes(tid)` call, specifically so untracked threads
  cost only a name comparison.
- **Bulk-read at the top, emit at the bottom.** One
  `getAllThreadIds() + getThreadInfo(tids)` beats N calls to
  `getThreadInfo(tid)`. See `emitSamples()` in `ThreadAllocBytesMetrics`.
- **Emit stable series.** If a label value is expected but temporarily
  absent (worker thread not yet spawned, peer briefly disconnected), emit
  `0.0` for it instead of skipping. Gaps break `rate()` and `increase()`
  downstream.
- **Simple gauges can inline the callback.** Both of these are fine and
  match existing style:
  ```java
  .callback(cb -> cb.call(ServerLOSPlayerDataCache.size()))
  .callback(cb -> cb.call(StormServerLosConfig.threads()))
  ```

---

## 7. Lazy class loading — the "metric never appeared" trap

`StormPrometheus` gives you the registry, but the actual `Counter.builder(...)`
call only fires when its enclosing class is loaded. Class load happens the
first time some other code touches the class — for advice-fed metrics, that's
the first call to the recorder from advice, which is the first time the
patched method runs.

Consequences:

- **If a metric is missing from `/metrics`, the advice hasn't fired yet.**
  Check with `/scripts` or a decisive test action; don't assume
  registration failed. Once the first observation lands, the histogram will
  show `_count = 1` and stay visible thereafter.
- **Callback-only metrics (`GaugeWithCallback`, `CounterWithCallback`) don't
  self-initialise.** Nothing else touches the class unless you make it. Two
  options:
  1. Load them from a class that *does* get touched early. Storm's example:
     `BitHeaderMetrics`'s `static {}` calls
     `ThreadAllocBytesMetrics.ensureStarted()` (a no-op public method whose
     side effect is triggering the callee's class initialisation) so the
     thread-alloc callback registers the moment a BitHeader patch fires.
  2. If there's no natural early-touch class, put the `ensureStarted()`
     call in a startup event handler.
- **`EventDispatchMetrics` chains to `GameTimeMetrics.ensureStarted()`** the
  same way. If you're adding a new callback-only metrics class, prefer
  hooking it to an existing early-loading `ensureStarted()` chain rather
  than adding a new event handler.

---

## 8. Gotchas that have bitten Storm

- **PZ's Prometheus HTTP server is disabled by default.** Metrics register
  fine into `PrometheusRegistry.defaultRegistry`, but nothing is exposed
  until the JVM is launched with `-DprometheusPort=<port>`. If you're
  testing locally, set `prometheusPort=9090` in `local.properties` (which
  `runProjectZomboidServer` forwards) or pass the flag on the exe command
  line for a client JVM. Absence of the port doesn't crash the collector
  code — it just silently isn't scraped.
- **Native histograms show as `_count`/`_sum` only if the scraper rejects
  them.** Not a Storm-side bug. Enable
  `--enable-feature=native-histograms` on Prometheus 2.x (GA in 3.x), and
  set `native_histogram_bucket_limit` in the scrape job.
- **PZ's own `pz_info` / `packet_*` / `player_*` land in the same registry.**
  Don't collide on names. `curl localhost:<port>/metrics | grep -E '^pz_'`
  before choosing a `pz_*` name.
- **A recorder called from a client thread does no harm.** Registration is
  idempotent, and the record call just increments an atomic. But **it
  distorts the metric** — the whole point of Storm's `_call_duration_seconds`
  suite is server-side timing. Runtime `GameServer.server` gating in the
  advice keeps client calls out of the histogram.
- **`static final` fields load once per classloader.** Storm's classloader
  setup means each metric class initializes exactly once, so double
  registration isn't a concern.

---

## 9. Consumer-mod recipe (short version)

For a Storm mod under `../project-zomboid-java-mod-*/`:

1. Class goes in `io.pzstorm.<mod>.metrics.<Feature>Metrics`.
2. Import `io.pzstorm.storm.metrics.StormPrometheus` and register into the
   same shared registry. Do **not** create your own `PrometheusRegistry`
   instance.
3. Prefix all metric names with `<mod>_` (matches `docs/metrics.md`
   convention). Never reuse `storm_*` or `pz_*` prefixes from a mod.
4. Same server-only rules: gate the advice on `GameServer.server`, register
   the patch under `StormEnv.isStormServer()` in your mod's
   `ZomboidMod.getClassTransformers()`.
5. Document each new metric in your mod's own equivalent of `docs/metrics.md`
   (or a section in its README). Storm's `docs/metrics.md` only tracks
   Storm-registered metrics.

---

## 10. Documentation

When adding a metric to Storm itself, extend the tables in
`docs/metrics.md` — the "Current metric reference" section is the source of
truth for operators. New rows follow the existing table shape:

```
| `<name>` | <Type> | <labels or —> | <one-line description>. |
```

If the new metric changes a canonical PromQL query pattern (e.g., a new
composite ratio), add a "Useful PromQL" snippet under the metric's section.
Operators grep for those.
