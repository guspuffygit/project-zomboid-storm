---
name: lua-hot-reload
description: Compile and run a Lua snippet in either live Project Zomboid JVM (client or dedicated server) via Storm's built-in `POST /reload` endpoint. Use when you need to run Lua against the running game's Kahlua VM (`LuaManager.env`) to inspect or mutate state without restarting.
---

# Lua hot-reload (Storm built-in)

Storm hosts `POST /reload` itself when a JVM is launched with `-Dstorm.http.port=<port>` and `-Dstorm.hotreload=true`. The Lua source is the **request body** — no file on disk is read. The chunk is compiled with `LuaCompiler.loadstring(...)` and executed in `LuaManager.env`; the chunk's return value comes back in the response.

Implementation: `io.pzstorm.storm.hotreload.HotReloadEndpoints#reload` → `LuaHotReload#run`.

## Workflow

Pick the JVM you want the Lua to run in:

- Client (Windows game) — `http://localhost:8089/reload`. Use for client-only state: rendering, UI, input, the client `ServerMap` mirror, the local player.
- Dedicated server (WSL) — `http://localhost:41798/reload`. Use for authoritative server state: `GameServer.IDToPlayerMap`, `ServerMap.zombieMap`, `AnimalInstanceManager`.

Both forms below work from anywhere — WSL shell, Windows shell, either JVM (see `CLAUDE.local.md`).

Inline for a one-liner:

```bash
curl -X POST --data-binary 'return getOnlinePlayers():size()' http://localhost:41798/reload
# -> OK: 1.0
```

File when iterating:

```bash
$EDITOR /tmp/snippet.lua
curl -X POST --data-binary @/tmp/snippet.lua http://localhost:41798/reload
```

Responses:

- `OK` — chunk ran, no return value.
- `OK: <value>` — chunk returned a value (rendered via `String.valueOf`).
- `ERROR: <message>` — compilation or execution failure (HTTP `200`, body carries the Lua error).
- `400` — empty request body.

On the dedicated server the Lua runs directly on the HTTP dispatcher thread (no `MainThreadQueue` drain needed, since the server has no render loop). On the client the call is queued onto the main thread and drained per-tick, so anything touching the render/GL context is safe.

## Required JVM flags

| Flag | Purpose |
|------|---------|
| `-Dstorm.http.port=<port>` | Starts Storm's HTTP server. Without this `/reload` is unreachable. |
| `-Dstorm.hotreload=true` | Registers `/reload` and `/eval`. Without this they 404. |

Both must be set on the JVM before launch — confirm with `ps -ef | grep storm.hotreload` on any Linux-side JVM, or grep the launcher batch script for the client. Changing the flags requires a restart.

## When to use the other workflow

For state only reachable from Java (private fields, Storm internals, JNA/FMOD), overloaded/generic methods, or anything not exposed to Lua, use `java-eval-hot-reload` instead.

## Reference

- `docs/http-api.md` — full endpoint reference (also covers `/health`, `/storm/version`, `/storm/server/players`, `/storm/ram-allocations`).
- `docs/server-configuration.md` — every bootstrap `-D` flag.
