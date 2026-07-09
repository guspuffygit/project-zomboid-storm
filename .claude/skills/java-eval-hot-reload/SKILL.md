---
name: java-eval-hot-reload
description: Compile a Java snippet and run it in either live Project Zomboid JVM (client or dedicated server) via Storm's built-in `POST /eval` endpoint. Use when you need type-checked, IDE-assisted Java access to game state that Lua can't reach (private fields, Storm internals, JNA/FMOD), or to call overloaded/generic methods cleanly.
---

# Java eval hot-reload (Storm built-in)

Storm hosts `POST /eval` itself when a JVM is launched with `-Dstorm.http.port=<port>` and `-Dstorm.hotreload=true`. The caller compiles `EvalScript.java` locally and POSTs the raw `.class` bytes; Storm defines the class in-memory and runs it. No shared classes dir, no `-Dstorm.hotreload.eval.*` flags. Each call:

1. Reads the raw bytes from the request body and checks the `0xCAFEBABE` class-file magic.
2. Defines `EvalScript` in a fresh one-shot `ClassLoader` parented to Storm's own loader.
3. Invokes `public static Object run()` reflectively.
4. Returns `String.valueOf(result)` — or `ERROR:\n<stack>` on failure.

Implementation: `io.pzstorm.storm.hotreload.HotReloadEndpoints#eval` → `JavaEvalRunner#run(byte[])`. Storm does **not** compile the source; the caller compiles `EvalScript.java` and ships the bytecode.

## When to use

- State only reachable from Java: private fields, Storm internals, JNA/FMOD.
- IDE-assisted exploration (imports, autocomplete) instead of Lua.
- Overloaded methods, generics, or classes that aren't Lua-exposed.

Prefer `lua-hot-reload` for anything already wrapped by a clean Lua API (`getPlayer()`, `getCell()`, `SafeHouse`, etc.).

## Workflow

Write `EvalScript.java` in the **default package** (no `package` line) with a `public static Object run()`:

```java
public class EvalScript {
    public static Object run() {
        return "server=" + zombie.network.GameServer.server;
    }
}
```

Compile and POST the `.class` bytes:

```bash
javac -cp "$PZ_JAR:$STORM_JAR" -d /tmp/eval EvalScript.java
curl -X POST --data-binary @/tmp/eval/EvalScript.class \
  -H 'Content-Type: application/java-vm' \
  http://localhost:8089/eval    # client
# or
curl -X POST --data-binary @/tmp/eval/EvalScript.class \
  -H 'Content-Type: application/java-vm' \
  http://localhost:41798/eval   # dedicated server
```

(The Storm jar filename is versioned, e.g. `storm-42.19.0_2.1.7-SNAPSHOT.jar`; glob the install dir for whichever one is present. `/tmp/eval` is just a scratch dir for `javac`'s output — Storm never reads it.)

No restart needed between iterations — recompile + curl. The classloader is freshly built per call, so there's no stale-class state across runs, and there's no shared directory that could serve a stale `.class` from a previous javac failure.

## Notes

- The loader isn't retained across calls — return primitive/string-rendered data; don't hand out references and expect them to outlive the call.
- Don't auto-serialize game objects (Jackson, etc.) — cyclic graphs and native references will explode. Build a string/JSON inside `run()`.
- Class name (`EvalScript`), default package, and `public static Object run()` signature are all hard-coded — match them exactly.
- `400` is returned for an empty request body. A body that isn't a valid class file (missing the `0xCAFEBABE` magic) comes back as `ERROR: request body is not a valid Java class file …` with a `200`.

## Required JVM flags

| Flag | Purpose |
|------|---------|
| `-Dstorm.http.port=<port>` | Starts Storm's HTTP server. |
| `-Dstorm.hotreload=true` | Registers `/eval` and `/reload`. |

## Reference

- `docs/http-api.md` — full endpoint reference.
- `docs/server-configuration.md` — every bootstrap `-D` flag.
