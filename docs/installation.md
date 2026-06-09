# Installation

Three install paths are covered below:

- **Steam Workshop (Client)** — the standard player install
- **Local Install (Development)** — build Storm from source and deploy locally
- **Dedicated Server** — server install on Windows or Linux, with Workshop or local Storm

## Steam Workshop (Client)

1. Subscribe to [Storm Mod Loader](https://steamcommunity.com/sharedfiles/filedetails/?id=3670772371) in the Steam Workshop.
2. Right click Project Zomboid in Steam Library, click Properties.
3. In General, under Launch Options, paste the line for your platform below.

### Windows
```text
-agentpath:../../workshop/content/108600/3670772371/mods/storm/bootstrap/agentlib.dll=storm-bootstrap.jar --
```

### Linux
```text
-javaagent:../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar --
```

### Mac
```text
-javaagent:../../../../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar --
```

When you start the game, the main screen menu should show the Storm version in the right bottom of the screen.

## Local Install (Development)

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

### Configure game launch options

1. Right click Project Zomboid in Steam Library, click Properties.
2. In General, under Launch Options, paste the line below.

#### Windows
```text
-DstormType=local "-agentpath:C:\Users\<user>\Zomboid\Workshop\storm\Contents\mods\storm\bootstrap\agentlib.dll=storm-bootstrap.jar" --
```

#### Linux / Mac
```text
-javaagent:~/Zomboid/Workshop/storm/Contents/mods/storm/bootstrap/storm-bootstrap.jar -DstormType=local --
```

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
