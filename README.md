# Storm Mod Loader

[![Maven Central](https://img.shields.io/maven-central/v/com.sentientsimulations/project-zomboid-storm)](https://central.sonatype.com/artifact/com.sentientsimulations/project-zomboid-storm)
[![License](https://img.shields.io/github/license/guspuffygit/project-zomboid-storm?logo=gnu)](https://www.gnu.org/licenses/)
[![Discord](https://img.shields.io/discord/823907021178798150?color=7289DA&label=discord&logo=discord&logoColor=white)](https://discord.gg/ZCmg9VsvSW)

Storm Mod Loader is a Java modding framework for Project Zomboid.

Successor to the original abandoned [Storm](https://github.com/pzstorm/storm)

## Installation

1. Subscribe to [Storm Mod Loader](https://steamcommunity.com/sharedfiles/filedetails/?id=3670772371) in the Steam Workshop.
2. Right click Project Zomboid in Steam Library, click Properties
3. In General, under Launch Options, copy and paste the line for your platform below into the input

#### Windows
```text
-agentpath:../../workshop/content/108600/3670772371/mods/storm/bootstrap/agentlib.dll=storm-bootstrap.jar --
```

#### Linux
```text
-javaagent:../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar --
```

#### Mac
```text
-javaagent:../../../../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar --
```

When you start the game, the main screen menu should show the Storm version in the right bottom of the screen.

## Local Install:

### Setup local.properties

1. Create a new file in the repo named `local.properties`
2. Specify these two required directories

* gameDir - Project Zomboid Installation directory
* zomboidDir - Project Zomboid configuration directory

```
gameDir=E:\\SteamLibrary\\steamapps\\common\\ProjectZomboid
zomboidDir=C:\\Users\\user\\Zomboid
```

### Deploy Storm Locally:

Windows:
```
.\gradlew.bat clean spotlessApply installBootstrap installStorm publishToMavenLocal
```

Linux / Mac:
```
./gradlew clean spotlessApply installBootstrap installStorm publishToMavenLocal
```

2. Right click Project Zomboid in Steam Library, click Properties
3. In General, under Launch Options, copy and paste the line below into the input

#### Windows
```text
-DstormType=local "-agentpath:C:\Users\<user>\Zomboid\Workshop\storm\Contents\mods\storm\bootstrap\agentlib.dll=storm-bootstrap.jar" --
```

#### Linux / Mac
```text
-javaagent:~/Zomboid/Workshop/storm/Contents/mods/storm/bootstrap/storm-bootstrap.jar -DstormType=local --
```

## Dedicated Server

Add `3670772371` to WorkshopItems in the server.ini file.

### Windows

The `StartServer64.bat` script does not pass extra arguments to the JVM, so you need to add the Storm flags directly to the `java.exe` command line in the bat file.

#### Workshop Install

Copy `StartServer64.bat` to `StartServer64-Storm.bat` and add the Storm flags before the `-cp` argument:

```bat
@setlocal enableextensions
@cd /d "%~dp0"

SET PZ_CLASSPATH=java/;java/projectzomboid.jar

".\jre64\bin\java.exe" ^
  -Djava.awt.headless=true ^
  -Dzomboid.steam=1 ^
  -Dzomboid.znetlog=1 ^
  -Dstorm.server=true ^
  -agentpath:./steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/agentlib.dll=storm-bootstrap.jar ^
  -XX:+UseZGC ^
  -XX:-CreateCoredumpOnCrash ^
  -XX:-OmitStackTraceInFastThrow ^
  -Xms16g ^
  -Xmx16g ^
  -Djava.library.path=natives/;natives/win64/;. ^
  -cp %PZ_CLASSPATH% ^
  zombie.network.GameServer ^
  -statistic 0 ^
  -servername yourserver ^
  %1 %2

PAUSE
```

#### Local Install

Add these flags to the java arguments:
```bat
-DstormType=local
-DLOG_LEVEL=DEBUG
```

### Linux

### Workshop Install

```bash
./start-server.sh \
  -javaagent:./steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar \
  -Dstorm.server=true \
  -- \
  -servername yourserver
```

### Local Install

```bash
./start-server.sh \
  -javaagent:~/Zomboid/Workshop/storm/Contents/mods/storm/bootstrap/storm-bootstrap.jar \
  -Dstorm.server=true \
  -DstormType=local \
  -DLOG_LEVEL=DEBUG \
  -- \
  -servername yourserver
```

## Developer Hot-Reload Endpoints

Storm ships two optional HTTP endpoints for iterating on a running game without restarting it:

- `POST /reload` — compiles and runs a Lua snippet in the live `LuaManager` environment.
- `GET /eval` — loads and runs a freshly compiled `EvalScript` Java class.

They are **off by default** and intended for local development only.

> ⚠️ Both endpoints execute arbitrary code in the game JVM. Only enable them on a trusted local
> machine — never on a public-facing client or server.

### Enabling

The endpoints ride on Storm's built-in HTTP server, so you need **both** of the first two flags below.
`/eval` additionally needs the classes directory.

| Flag | Purpose |
|------|---------|
| `-Dstorm.http.port=<port>` | Starts Storm's HTTP server on `<port>` (required for any endpoint). |
| `-Dstorm.hotreload=true` | Registers `/reload` and `/eval`. Without it they return `404`. |
| `-Dstorm.hotreload.eval.classes=<dir>` | Directory holding the compiled `EvalScript.class`. Required by `/eval`. |
| `-Dstorm.hotreload.eval.source=<dir>` | Optional. Directory holding `EvalScript.java`; enables a staleness guard that fails fast when the source is newer than the compiled class. |

Example (Linux dedicated server, local install):

```bash
./start-server.sh \
  -javaagent:~/Zomboid/Workshop/storm/Contents/mods/storm/bootstrap/storm-bootstrap.jar \
  -Dstorm.server=true \
  -DstormType=local \
  -Dstorm.http.port=41798 \
  -Dstorm.hotreload=true \
  -Dstorm.hotreload.eval.classes=/path/to/eval-classes \
  -- \
  -servername yourserver
```

On the client, add the same `-Dstorm.http.port` / `-Dstorm.hotreload` flags to the game's launch
options. The client and server each run their own HTTP server, so give them different ports
(e.g. `8089` for the client, `41798` for the server).

### `POST /reload` — Lua

Send the Lua source as the **raw request body**. It is compiled with Kahlua and run in the game's
`LuaManager.env`; the chunk's return value comes back in the response. On the client the snippet runs
on the game's `MainThread`, so `getPlayer()`, `getCell()`, the UI, etc. are all available.

```bash
curl -X POST --data-binary 'return "pong"' http://localhost:41798/reload
# -> OK: pong
```

Responses:

- `OK` — chunk ran, no return value.
- `OK: <value>` — chunk returned a value.
- `ERROR: <message>` — compilation or execution failed (HTTP `200`, body carries the Lua error).
- `400` — empty request body.

### `GET /eval` — Java

Write an `EvalScript.java` in the **default package** (no `package` line) with a
`public static Object run()` method, compile it into the directory named by
`-Dstorm.hotreload.eval.classes`, then call the endpoint. A fresh classloader is created on every
request, so each call picks up the latest recompiled class. The script has full access to `zombie.*`
and `io.pzstorm.*`.

```java
// EvalScript.java  (default package — no package declaration)
public class EvalScript {
    public static Object run() {
        return "server=" + zombie.network.GameServer.server;
    }
}
```

```bash
# Compile against projectzomboid.jar + the Storm jar, output into the configured classes dir:
javac -cp '<projectzomboid.jar>:<storm.jar>' -d /path/to/eval-classes EvalScript.java
curl http://localhost:41798/eval
# -> server=true
```

The endpoint returns `String.valueOf(run())`, or an `ERROR:` line with a stack trace if the class is
missing, stale, or `run()` throws.

### Client vs server

Both endpoints work on the client and the dedicated server. On the server they run directly on the
HTTP thread; on the client the work is dispatched to the game's `MainThread` so it never touches Lua
or rendering state off-thread.
