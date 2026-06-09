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
