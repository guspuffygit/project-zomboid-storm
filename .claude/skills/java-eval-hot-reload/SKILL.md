---
name: java-eval-hot-reload
description: Compile a Java snippet and run it in either live Project Zomboid JVM (client or dedicated server) via Storm's built-in `GET /eval` endpoint. Use when you need type-checked, IDE-assisted Java access to game state that Lua can't reach (private fields, Storm internals, JNA/FMOD), or to call overloaded/generic methods cleanly.
---

# Java eval hot-reload (Storm built-in)

Storm hosts `GET /eval` itself when a JVM is launched with `-Dstorm.http.port=<port>`, `-Dstorm.hotreload=true`, and `-Dstorm.hotreload.eval.classes=<dir>`. The client (port 8089) and dedicated server (port 41798) share a classes dir, so one `javac` invocation feeds both endpoints. Each call:

1. Opens a fresh `URLClassLoader` parented to the Storm class loader, rooted at the classes dir.
2. Loads `EvalScript` from the **default package** in that dir.
3. Invokes `public static Object run()` reflectively.
4. Closes the loader.
5. Returns `String.valueOf(result)` — or `ERROR:\n<stack>` on failure.

Implementation: `io.pzstorm.storm.hotreload.HotReloadEndpoints#eval` → `JavaEvalRunner#run`. Storm does **not** compile the source; the caller compiles `EvalScript.java` into the classes dir before each `curl`.

## When to use

- State only reachable from Java: private fields, Storm internals, JNA/FMOD.
- IDE-assisted exploration (imports, autocomplete) instead of Lua.
- Overloaded methods, generics, or classes that aren't Lua-exposed.

Prefer `lua-hot-reload` for anything already wrapped by a clean Lua API (`getPlayer()`, `getCell()`, `SafeHouse`, etc.).

## Workflow

Put `EvalScript.java` in the eval source dir (see CLAUDE.local.md) in the **default package** (no `package` line) with a `public static Object run()`:

```java
public class EvalScript {
    public static Object run() {
        return "server=" + zombie.network.GameServer.server;
    }
}
```

Compile and call (paths from CLAUDE.local.md):

```bash
javac -cp "$PZ_JAR:$STORM_JAR" -d "$EVAL_CLASSES" "$EVAL_SRC/EvalScript.java"
curl http://localhost:8089/eval    # client
# or
curl http://localhost:41798/eval   # dedicated server
```

(The Storm jar filename is versioned, e.g. `storm-42.19.0_2.1.7-SNAPSHOT.jar`; glob the install dir for whichever one is present.)

To verify the configured dirs on a running JVM:

```bash
ps -ef | grep -oP 'storm\.hotreload\.eval\.\w+=\S+'   # any Linux-side JVM
```

For the Windows client, grep the same `-D` flags from its launcher batch script.

No restart needed between iterations — recompile + curl. The classloader is freshly built per call, so there's no stale-class state across runs.

## Source-staleness guard

When `-Dstorm.hotreload.eval.source=<dir>` is set, `/eval` compares mtimes on each call and fails fast with `ERROR: stale class …` when `EvalScript.java` is newer than the compiled `EvalScript.class`. Catches "I forgot to recompile" before it returns a confusing old result.

## Notes

- The loader is closed after each call — return primitive/string-rendered data; don't hand out references and expect them to outlive the call.
- Don't auto-serialize game objects (Jackson, etc.) — cyclic graphs and native references will explode. Build a string/JSON inside `run()`.
- Class name (`EvalScript`), default package, and `public static Object run()` signature are all hard-coded — match them exactly.

## Required JVM flags

| Flag | Purpose |
|------|---------|
| `-Dstorm.http.port=<port>` | Starts Storm's HTTP server. |
| `-Dstorm.hotreload=true` | Registers `/eval` and `/reload`. |
| `-Dstorm.hotreload.eval.classes=<dir>` | Directory holding the compiled `EvalScript.class`. |
| `-Dstorm.hotreload.eval.source=<dir>` | Optional staleness guard for `EvalScript.java`. |

## Reference

- `docs/http-api.md` — full endpoint reference.
- `docs/server-configuration.md` — every bootstrap `-D` flag.
