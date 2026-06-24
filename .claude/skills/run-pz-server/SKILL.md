---
name: run-pz-server
description: Run the Storm dedicated server locally via `./gradlew runProjectZomboidServer`, send commands to it from a script, and tear it down cleanly. Use when iterating on Storm or a mod and you need a real server JVM, or when scripting server-driven tests.
---

# Run the Storm dedicated server

`./gradlew runProjectZomboidServer` passes `-DstormType=local` and `-Dstorm.server=true`. The bootstrap agent uses `-Dstorm.server=true` to target `GameServer`; `-DstormType=local` makes it look under `~/Zomboid/Workshop/` instead of the Steam workshop path.

Prerequisite symlink (one-time) — point `~/Zomboid/Workshop/storm` at your Storm Workshop item dir (parent of `Contents/mods/storm`; see CLAUDE.local.md for `Storm Install`):

```bash
ln -s <storm-workshop-dir> ~/Zomboid/Workshop/storm
```

Always run from WSL. Wait for `*** SERVER STARTED ****` — startup takes ~30s. Server debug logs land at `~/Zomboid/Logs/<date>_DebugLog-server.txt` and `~/Zomboid/Logs/storm/{main,debug}.log`.

The gradle task does **not** pass `-Dstorm.http.port` or `-Dstorm.hotreload=true` by default, so a server launched via it has no `/eval` / `/reload` / `/metrics`. If you need those, launch `start-server.sh` directly with the extra flags (see `CLAUDE.local.md` for the conventional port `41798` and eval classes dir).

## Scripted: send commands via FIFO

Used when you need to start the server, run commands, and tear it down without an interactive terminal.

```bash
FIFO="/tmp/pz-server-stdin"
OUTPUT="/tmp/pz-server-output.log"
rm -f "$FIFO" "$OUTPUT"
mkfifo "$FIFO"
touch "$OUTPUT"

# cd first, then pipe — don't nest the cd in a subshell with the pipe
cd <storm-project-root>
cat "$FIFO" | ./gradlew runProjectZomboidServer >> "$OUTPUT" 2>&1 &

# Wait for startup (~30s). Poll every 2s, timeout after 90s.
for i in $(seq 1 45); do
  grep -q 'SERVER STARTED' "$OUTPUT" 2>/dev/null && break
  sleep 2
done

echo "ping" > "$FIFO"
sleep 5
grep -E 'ping|pong' "$OUTPUT"   # expect "pong"

echo "quit" > "$FIFO"
sleep 3
rm -f "$FIFO"
```

## Cleanup if the server doesn't shut down

```bash
pkill -f "ProjectZomboid"
pkill -f "GameServer"
pkill -f "gradlew"
sleep 3
```

## Build before running

`installStorm` is a `Sync` task; it fails with "Permission denied" on `agentlib.dll` / `storm.jar` while a client or dedicated server is running — the JVM memory-maps those files. Stop the client/server first.
