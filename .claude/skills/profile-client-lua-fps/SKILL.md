---
name: profile-client-lua-fps
description: Attribute a client-side FPS drop to a specific Lua function and owning mod by sampling the running client via Storm's `POST /eval`. Use when the client is running low FPS and you need ground truth on where the frame time is going — not a static Lua review, not a guess from grep. Produces per-callback and per-file attribution and confirms with a live A/B unhook.
---

# Profile client Lua FPS (via Storm `/eval`)

You want to answer "what Lua is eating my frame time?" against the running client, not by reading code. The client already exposes everything you need through Storm's `POST /eval` — you inject a Java sampler, walk Kahlua's call-frame stack a few thousand times, and read the histogram back.

Every step here runs on the real client. Nothing is reset. The A/B step at the bottom is fully reversible and restores the client to the exact state you found it in.

## When to use

- Client is running at a fraction of its normal FPS and you want to know **which mod** and **which function** is responsible.
- You have a hypothesis (a `render()` override, an `OnTick` handler) and want to confirm/deny it against live data before editing.
- You need to distinguish "hot every-frame Lua" from "hot Java lighting/streaming that Lua just triggered" — the sampler shows both.

Prefer this over: reading mod source top-down (misses cross-mod interactions), a JFR recording (needs a Windows-side restart, ships you a symbol-poor mixed profile), or `grep Events.OnTick` (finds every registration, not the expensive one).

## Prerequisites

- Client launched with `-Dstorm.http.port=8089 -Dstorm.hotreload=true` — check with `curl -s http://localhost:8089/storm/version`.
- MCP: `mcp__pzmcp__pz_java_eval` with `target: "client"`. (Bare `curl … /eval` works too, but you have to compile locally.)
- See `java-eval-hot-reload` for the compile+POST model. This skill layers a specific pattern on top of it.

## Critical: the sampler MUST run on a background thread

`/eval` runs on the client's `MainThread` (drained via Storm's `MainThreadQueue` from the render loop). A long-running eval blocks the game and — worse — every stack you sample of `MainThread` is sitting in `Thread.getStackTrace` because that *is* what MainThread is doing right now (running your eval). You get 100% self-samples and no signal.

Two consequences:

1. The eval spawns a daemon `Thread` that does the sampling, then returns immediately. The daemon samples the real `MainThread` while it's back running Lua.
2. Statics don't persist across evals (each call gets a fresh one-shot `ClassLoader`). Cross-eval handoff goes through `System.setProperty(...)` — the daemon writes the result, a second eval reads it.

Anything you build here follows this shape. Ignore it and you'll spend an hour convinced the bottleneck is `Thread.getStackTrace`.

## Baseline: what is the FPS right now?

`zombie.GameWindow.averageFPS` is a `public static float` you can read directly. Always take a baseline before and after each intervention:

```java
public class EvalScript {
    public static Object run() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000);  // let the moving average settle
                float min = Float.MAX_VALUE, max = 0, sum = 0; int n = 0;
                long end = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < end) {
                    float f = zombie.GameWindow.averageFPS;
                    min = Math.min(min, f); max = Math.max(max, f); sum += f; n++;
                    Thread.sleep(100);
                }
                System.setProperty("perf.fpsprobe.result",
                    String.format("min=%.1f max=%.1f avg=%.1f n=%d", min, max, sum / n, n));
            } catch (Throwable e) { System.setProperty("perf.fpsprobe.result", "ERROR " + e); }
        }, "FpsProbe");
        t.setDaemon(true);
        t.start();
        return "fps probe started";
    }
}
```

Then in a second eval, poll `System.getProperty("perf.fpsprobe.result")` (see "Draining a result" at the bottom).

## Step 1: coarse thread sample — is Lua even the bottleneck?

Sample the two threads that matter:

- **`MainThread`** — game logic + Lua. If it's mostly in `KahluaThread.luaMainloop`, this skill is the right tool. If it's mostly in physics/streaming Java, it's not.
- **`main`** — the GL render thread. If it sits in `zombie.core.SpriteRenderer.waitForReadyState`, the render thread is idle waiting on `MainThread` — logic-bound, use this skill. If it's busy in GL calls, it's GPU-bound and this skill won't help.

```java
public class EvalScript {
    public static Object run() {
        Thread mt = null, rt = null;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("MainThread")) mt = t;
            if (t.getName().equals("main"))       rt = t;
        }
        if (mt == null || rt == null) return "threads not found";
        final Thread fmt = mt, frt = rt;
        Thread sampler = new Thread(() -> {
            try {
                java.util.HashMap<String, Integer> leafMt = new java.util.HashMap<>();
                java.util.HashMap<String, Integer> zMt    = new java.util.HashMap<>();
                java.util.HashMap<String, Integer> leafRt = new java.util.HashMap<>();
                java.util.HashMap<String, Integer> zRt    = new java.util.HashMap<>();
                int n = 0, kahluaMt = 0;
                long end = System.currentTimeMillis() + 6000;   // 6s @ 2ms → ~2500 samples
                while (System.currentTimeMillis() < end) {
                    n++;
                    sample(fmt, leafMt, zMt);
                    if (containsKahlua(fmt)) kahluaMt++;
                    sample(frt, leafRt, zRt);
                    Thread.sleep(2);
                }
                StringBuilder sb = new StringBuilder();
                sb.append("samples=").append(n).append(" kahluaMt=").append(kahluaMt).append("\n");
                sb.append("== MainThread leaf ==\n");                  top(sb, leafMt, 14, n);
                sb.append("== MainThread nearest zombie/kahlua frame ==\n"); top(sb, zMt, 20, n);
                sb.append("== main(render) leaf ==\n");                top(sb, leafRt, 10, n);
                sb.append("== main(render) nearest zombie frame ==\n"); top(sb, zRt, 12, n);
                System.setProperty("perf.sampler.result", sb.toString());
            } catch (Throwable e) { System.setProperty("perf.sampler.result", "ERROR " + e); }
        }, "PerfSampler");
        sampler.setDaemon(true);
        sampler.start();
        return "sampler started for 6s";
    }
    static void sample(Thread t, java.util.HashMap<String, Integer> leaf,
                                 java.util.HashMap<String, Integer> zf) {
        StackTraceElement[] st = t.getStackTrace();
        if (st.length == 0) return;
        leaf.merge(st[0].getClassName() + "." + st[0].getMethodName(), 1, Integer::sum);
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("zombie.") || cn.startsWith("se.krka.kahlua") || cn.startsWith("io.pzstorm")) {
                zf.merge(cn + "." + e.getMethodName(), 1, Integer::sum);
                break;
            }
        }
    }
    static boolean containsKahlua(Thread t) {
        for (StackTraceElement e : t.getStackTrace())
            if (e.getClassName().startsWith("se.krka.kahlua")) return true;
        return false;
    }
    static void top(StringBuilder sb, java.util.HashMap<String, Integer> m, int k, int total) {
        java.util.List<java.util.Map.Entry<String,Integer>> es = new java.util.ArrayList<>(m.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < Math.min(k, es.size()); i++)
            sb.append(String.format("  %5.1f%%  %s%n", 100.0 * es.get(i).getValue() / total, es.get(i).getKey()));
    }
}
```

Interpretation:

- `kahluaMt` percentage says how much of `MainThread` was in Lua. If it's >50%, keep going with Step 2. If it's low, the bottleneck isn't Lua — profile the "nearest zombie frame" Java hotspots (streaming, lighting, model dispatch) with regular tools.
- `main(render)` leaf overwhelmingly in `waitForReadyState` → render thread starving on `MainThread` → this skill applies.

## Step 2: Kahlua call-frame sample — which Lua function?

`StackTraceElement`s only cover Java. Kahlua interprets bytecode in `luaMainloop` — every Lua frame collapses into one Java frame. To attribute to Lua you have to walk Kahlua's own call-frame stack.

The pieces:

- `zombie.Lua.LuaManager.thread` — the singleton `KahluaThread`.
- `KahluaThread.currentCoroutine` — the active `Coroutine`.
- `Coroutine.callFrameStack` (private `LuaCallFrame[]`) — reflection required.
- `Coroutine.callFrameTop` (private `int`).
- `LuaCallFrame.closure` — the `LuaClosure` (public). `null` means it's a Java call frame; check `.javaFunction`.
- `LuaClosure.prototype.name` + `.prototype.filename` — the function name and source file. `filename` in the client is a full workshop path like `.../mods/Talis New Music/42/media/lua/client/main/NMClientMain.lua`, which is what identifies the owning mod.

Sample the deepest frame (leaf = the actually-running function) and the root frame (frame 0 = the outermost registered callback):

```java
import java.lang.reflect.Field;
import se.krka.kahlua.vm.*;

public class EvalScript {
    public static Object run() throws Exception {
        final Field fStack = Coroutine.class.getDeclaredField("callFrameStack");
        final Field fTop   = Coroutine.class.getDeclaredField("callFrameTop");
        fStack.setAccessible(true);
        fTop.setAccessible(true);
        Thread sampler = new Thread(() -> {
            try {
                java.util.HashMap<String, Integer> root = new java.util.HashMap<>();
                java.util.HashMap<String, Integer> leaf = new java.util.HashMap<>();
                int n = 0, luaActive = 0;
                long end = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < end) {
                    n++;
                    try {
                        KahluaThread kt = zombie.Lua.LuaManager.thread;
                        Coroutine co = kt == null ? null : kt.currentCoroutine;
                        if (co != null) {
                            LuaCallFrame[] st = (LuaCallFrame[]) fStack.get(co);
                            int top = fTop.getInt(co);
                            if (top > 0 && st != null) {
                                String r = name(st, 0), l = name(st, top - 1);
                                if (r != null) { luaActive++; root.merge(r, 1, Integer::sum); }
                                if (l != null) leaf.merge(l, 1, Integer::sum);
                            }
                        }
                    } catch (Throwable ignore) {}
                    Thread.sleep(2);
                }
                StringBuilder sb = new StringBuilder();
                sb.append("samples=").append(n).append(" luaActive=").append(luaActive)
                  .append(" (").append(100 * luaActive / Math.max(1, n)).append("%)\n");
                sb.append("== ROOT closures (event callback attribution) ==\n"); top(sb, root, 25, n);
                sb.append("== LEAF closures ==\n");                                top(sb, leaf, 25, n);
                System.setProperty("perf.luasampler.result", sb.toString());
            } catch (Throwable e) { System.setProperty("perf.luasampler.result", "ERROR " + e); }
        }, "LuaPerfSampler");
        sampler.setDaemon(true);
        sampler.start();
        return "lua sampler started for 8s";
    }
    static String name(LuaCallFrame[] st, int i) {
        LuaCallFrame f = st[i];
        if (f == null) return null;
        LuaClosure c = f.closure;
        if (c == null) return f.javaFunction == null ? null : "[java] " + f.javaFunction;
        String fn = c.prototype.filename;
        if (fn != null) {
            int idx = Math.max(fn.lastIndexOf('/'), fn.lastIndexOf('\\'));
            fn = idx >= 0 ? fn.substring(idx + 1) : fn;
        }
        return String.valueOf(c.prototype.name) + " @ " + fn;
    }
    static void top(StringBuilder sb, java.util.HashMap<String, Integer> m, int k, int total) {
        java.util.List<java.util.Map.Entry<String,Integer>> es = new java.util.ArrayList<>(m.entrySet());
        es.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < Math.min(k, es.size()); i++)
            sb.append(String.format("  %5.1f%%  %s%n", 100.0 * es.get(i).getValue() / total, es.get(i).getKey()));
    }
}
```

Interpreting the output:

- **ROOT %** — attribution to the outer event callback. This is what tells you which mod's `onTick`/`render`/`prerender` is dominating. A single closure at >30% is usually the answer.
- **LEAF %** — the specific hot function. Often deeper than the root (e.g. `render` → `some helper` → `addSquareCandidates`).
- `luaActive` at ~90% + one root dominating = a Lua-registered event handler is the whole problem. Move to Step 4.
- The full workshop path is on the closure's `prototype.filename` (only the basename is printed in the report). Grep the full path once you've picked a suspect to find the mod's workshop ID: `find $STEAM/steamapps/workshop/content/108600 -path '*<basename>*'`.

## Step 3: inventory the hot event registrations (optional)

If you want to see every callback attached to a hot event (with its owning file), reflect `LuaEventManager.EventMap` — private `HashMap<String, Event>` where `Event.callbacks` is a public `ArrayList<LuaClosure>`:

```java
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;

public class EvalScript {
    public static Object run() throws Exception {
        Field f = LuaEventManager.class.getDeclaredField("EventMap");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        HashMap<String, Event> map = (HashMap<String, Event>) f.get(null);
        String[] hot = { "OnTick", "OnPostRender", "OnPreUIDraw", "OnPostUIDraw",
                         "OnRenderTick", "OnPlayerUpdate", "OnTickEvenPaused", "OnFETick" };
        StringBuilder sb = new StringBuilder();
        for (String name : hot) {
            Event e = map.get(name);
            if (e == null) continue;
            sb.append("== ").append(name).append("  (").append(e.callbacks.size()).append(")\n");
            for (LuaClosure c : e.callbacks)
                sb.append("   ").append(c.prototype.name)
                  .append("  <-  ").append(c.prototype.filename).append("\n");
        }
        return sb.toString();
    }
}
```

Useful for: matching the profile's ROOT closure back to a specific file, spotting duplicate registrations from `/reload` stacking, or filtering to "callbacks whose filename contains `<mod-id>`".

## Step 4: prove it with a live A/B unhook

Static analysis gives correlation. Removing the suspected callback and watching FPS jump gives causation. This is safe: `Event.callbacks` is iterated on `MainThread`, and `/eval` runs on `MainThread`, so the list mutation is race-free. The stash pattern below always restores it — do the restore in the same session.

Unhook + stash:

```java
import java.lang.reflect.Field;
import java.util.HashMap;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;

public class EvalScript {
    public static Object run() throws Exception {
        Field f = LuaEventManager.class.getDeclaredField("EventMap");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        HashMap<String, Event> map = (HashMap<String, Event>) f.get(null);
        Event ev = map.get("OnTick");                 // ← the event name
        LuaClosure found = null;
        for (LuaClosure c : ev.callbacks) {
            String fn = c.prototype.filename;
            if (fn != null && fn.contains("<mod-id-or-file-fragment>")) { found = c; break; }
        }
        if (found == null) return "target closure not found";
        ev.callbacks.remove(found);
        LuaManager.env.rawset("__perf_stash", found);  // stash on the Lua env for later restore
        return "removed. callbacks=" + ev.callbacks.size() + " FPS=" + zombie.GameWindow.averageFPS;
    }
}
```

Run the FPS-baseline probe from the top of this file. Compare avg with/without.

Restore:

```java
import java.lang.reflect.Field;
import java.util.HashMap;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;

public class EvalScript {
    public static Object run() throws Exception {
        Object stash = LuaManager.env.rawget("__perf_stash");
        if (!(stash instanceof LuaClosure)) return "stash missing!";
        Field f = LuaEventManager.class.getDeclaredField("EventMap");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        HashMap<String, Event> map = (HashMap<String, Event>) f.get(null);
        Event ev = map.get("OnTick");
        LuaClosure c = (LuaClosure) stash;
        if (!ev.callbacks.contains(c)) ev.callbacks.add(c);
        LuaManager.env.rawset("__perf_stash", null);
        return "restored. callbacks=" + ev.callbacks.size();
    }
}
```

Always run baseline → unhook → baseline → restore → baseline. The last baseline should match the first — it's your proof that you didn't leave the client in a mutated state.

## Draining a result from a background sampler

Every sampler here returns immediately after spawning its daemon. Read the result with a second eval that checks `System.getProperty(...)` ONCE and returns:

```java
public class EvalScript {
    public static Object run() {
        String r = System.getProperty("perf.sampler.result");   // or perf.luasampler.result / perf.fpsprobe.result
        if (r == null) return "not ready";
        System.clearProperty("perf.sampler.result");
        return r + "\nFPS=" + zombie.GameWindow.averageFPS;
    }
}
```

**Never `Thread.sleep` in the drainer.** The eval body runs ON `MainThread` (via the `OnRenderTickEvent` → `drainMainThreadQueue` handler), so a sleep-poll loop freezes the game for its full duration — each 400ms sleep is a 400ms frame stall the player feels and any concurrent hitch profiler records. A live session's hitch profile attributed 12.2% of hitch time to `StormEventDispatcher$EventHandlerMethod.invoke` that turned out to be exactly this: the session's own drainer evals sleeping on MainThread. Instead, wait out the sampler window OUTSIDE the game (background `sleep <window+3> && echo done` in the shell), then send the single-check drainer above; if it returns "not ready", wait longer outside and send it again.

Use a distinct property key per sampler if you're running more than one, otherwise the drainer will grab whichever finished first.

## Gotchas

- **Sampling `MainThread` synchronously = self-sample.** If your sampler runs inside the eval body (not a spawned thread), 100% of samples will be `Thread.getStackTrace` in your own code. Always spawn.
- **Statics don't persist.** Each `/eval` gets a fresh `ClassLoader`. Cross-eval state goes through `System.setProperty` or `LuaManager.env.rawset` — never a `static` field.
- **`Coroutine.callFrameStack` / `callFrameTop` are private.** Reflection with `setAccessible(true)`. `LuaCallFrame.closure` and `LuaClosure.prototype` are public.
- **`LuaManager.thread` is the field, not `KahluaThread.thread`.** There's no static self-reference on `KahluaThread`.
- **The `prototype.filename` on a mod file is the full workshop path** on Windows (e.g. `E:/SteamLibrary/steamapps/workshop/content/108600/<id>/mods/<name>/…`). The workshop ID between `content/108600/` and `/mods/` is the mod's Steam ID — the fastest way to identify what to blame.
- **Sample interval.** `Thread.sleep(2)` gives ~500 samples/sec. That's enough resolution for hot paths in 6–8s; go longer if the distribution is flat.
- **The sampler thread is a daemon.** Don't rely on it running past its own `end` deadline — its only job is to write the property before it exits.

## What "ground truth" actually looks like

A real session that solved a 240→50 FPS regression, condensed:

- Step 1 showed `MainThread` 91% in Kahlua; `main` 77% waiting on it → logic-bound in Lua.
- Step 2 showed one ROOT closure — `onTick @ NMClientMain.lua` — at 79.4% of all Lua time, and a LEAF — `addSquareCandidates @ NMClientVehicleAttachmentResolver.lua` — at 52.4%. Full filename identified the workshop mod ID.
- Step 4 removed that one `OnTick` callback: avg FPS 42 → 247 (peaks 348). Restored: back to 42. Client left in original state.

Total wall time: ~5 minutes of eval calls. No mod code was edited to identify the culprit.

## Reference

- `java-eval-hot-reload` (this repo) — the underlying `POST /eval` mechanism this skill uses.
- `lua-hot-reload` (this repo) — the sibling `POST /reload` endpoint (useful for read-only Lua state you don't need reflection for).
- `docs/http-api.md` — Storm HTTP endpoint reference.
- `CLAUDE.local.md` — client vs server ports, log locations, gotcha about not confusing the two.
