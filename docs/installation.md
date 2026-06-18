# Installation

Storm is a **server-side** framework. The install paths covered below all target the dedicated-server JVM — players connecting to a Storm-enabled server do **not** install Storm themselves and do not edit their game's launch options; required mods reach them through the vanilla workshop / mod-download flow the server advertises in `server.ini`.

Two paths are covered below:

- **Dedicated Server** — production server install on Windows or Linux, with Workshop or locally-built Storm
- **Local Install (Development)** — build Storm from source and deploy it into a local dedicated server for iteration

## Local Install (Development)

For iterating on Storm itself or a Storm-based mod against a local dedicated server.

### Setup local.properties

1. Create a new file in the repo named `local.properties`.
2. Specify these two required directories:

* `gameDir` — Project Zomboid installation directory
* `zomboidDir` — Project Zomboid configuration directory

```
gameDir=E:\\SteamLibrary\\steamapps\\common\\ProjectZomboid
zomboidDir=C:\\Users\\user\\Zomboid
```

### Build and deploy Storm

Windows:
```
.\gradlew.bat clean spotlessApply installBootstrap installStorm publishToMavenLocal
```

Linux / Mac:
```
./gradlew clean spotlessApply installBootstrap installStorm publishToMavenLocal
```

This deploys Storm into `<zomboidDir>/Workshop/storm/...`, which the "Local Install" subsections in **Dedicated Server** below point at via `-DstormType=local`.

## Dedicated Server

Add `3670772371` to `WorkshopItems` in the server's `server.ini` file.

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

#### Workshop Install

```bash
./start-server.sh \
  -javaagent:./steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar \
  -Dstorm.server=true \
  -- \
  -servername yourserver
```

#### Local Install

```bash
./start-server.sh \
  -javaagent:~/Zomboid/Workshop/storm/Contents/mods/storm/bootstrap/storm-bootstrap.jar \
  -Dstorm.server=true \
  -DstormType=local \
  -DLOG_LEVEL=DEBUG \
  -- \
  -servername yourserver
```

For a production-ready launcher with workshop refresh, signal-chaining for core dumps, and Storm tuning flags pre-configured, see [Server Configuration → Production launcher example](server-configuration.md#production-launcher-example-linux).

### Hosted Server (no shell access)

Many managed hosts do not let you edit `start-server.sh` / `StartServer64.bat`. JVM arguments instead come from the launcher config (`ProjectZomboid64.json`), which the host's panel usually surfaces as **JVM Arguments**, **vmArgs**, or **Launch Options**.

Storm's `-javaagent` / `-agentpath` argument points at a file inside the workshop content directory (`storm-bootstrap.jar` / `agentlib.dll`). That file only exists **after** Steam has downloaded workshop item `3670772371`. If you add the JVM arg before the mod is on disk, the JVM refuses to start and your host hands you a boot loop.

Install in three passes — workshop entry, first boot, JVM args.

#### 1. Add Storm to `server.ini`

Open the server config (commonly `<server-name>.ini` or `servertest.ini` in the host's web panel) and add Storm to both lists:

```
WorkshopItems=3670772371
Mods=storm-core-b42
```

If the lists already have entries, append to them with `;` as the separator (e.g. `WorkshopItems=2169435993;3670772371`). Use the same pattern for any other Storm-based mod: its **workshop ID** goes in `WorkshopItems`, and the **mod ID** from its `mod.info` goes in `Mods`.

#### 2. Start the server once to download Storm

Start the server through the host's UI. Steam downloads workshop item `3670772371`, dropping the bootstrap artifacts at:

```
<server root>/steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar
<server root>/steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/agentlib.dll
```

Storm is **not active yet** on this run — no agent is attached. Once that directory exists on disk, stop the server.

#### 3. Add Storm's JVM args to `ProjectZomboid64.json`

Open `ProjectZomboid64.json` (or whatever the host calls its JVM-args field) and add Storm's flags to the `vmArgs` array. Keep all existing entries in place and add Storm's on top.

Linux host:
```json
{
    "vmArgs": [
        "-javaagent:./steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar",
        "-Dstorm.server=true"
    ]
}
```

Windows host:
```json
{
    "vmArgs": [
        "-agentpath:./steamapps/workshop/content/108600/3670772371/mods/storm/bootstrap/agentlib.dll=storm-bootstrap.jar",
        "-Dstorm.server=true"
    ]
}
```

If your host exposes JVM args as a single text box instead of editable JSON, paste the two flags on their own lines (or space-separated, depending on the host's format) alongside the existing args.

#### 4. Restart the server

On the next start the bootstrap agent attaches and Storm initializes. Verify by checking `<zomboidDir>/Logs/storm/main.log` for Storm's startup banner, or send the `ping` console command if your host exposes one — Storm replies with `pong`.
