# Storm Mod Loader

[![Maven Central](https://img.shields.io/maven-central/v/com.sentientsimulations/project-zomboid-storm)](https://central.sonatype.com/artifact/com.sentientsimulations/project-zomboid-storm)
[![License](https://img.shields.io/github/license/guspuffygit/project-zomboid-storm?logo=gnu)](https://www.gnu.org/licenses/)
[![Discord](https://img.shields.io/discord/823907021178798150?color=7289DA&label=discord&logo=discord&logoColor=white)](https://discord.gg/ZCmg9VsvSW)

Storm Mod Loader is a Java modding framework for Project Zomboid.

Successor to the original abandoned [Storm](https://github.com/pzstorm/storm).

## Quickstart (Client)

1. Subscribe to [Storm Mod Loader](https://steamcommunity.com/sharedfiles/filedetails/?id=3670772371) in the Steam Workshop.
2. Right click Project Zomboid in Steam Library, click Properties.
3. In General, under Launch Options, paste the line for your platform below.

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

When you start the game, the main screen menu should show the Storm version in the bottom right of the screen.

For local development install or dedicated server setup, see [Installation](docs/installation.md).

## What Storm Does

Storm rewrites a chunk of the game's bytecode at load time to:

- **Patch vanilla bugs** that affect multiplayer (cross-player action cancels, zombie ID collisions, whisper case-sensitivity, …)
- **Lift hardcoded server limits** (configurable tick rate, parallel LOS pipeline, packet rate limit removed, raised zombie cull cap)
- **Surface event hooks to Java mods** (packet receipt, chat, ~190 Lua event bridges)
- **Extend the mod loader** with annotation-driven HTTP endpoints, server commands, and event handlers

See [What Storm Changes](docs/what-storm-changes.md) for the full list.

## Documentation

- [Installation](docs/installation.md) — Steam Workshop, local development install, dedicated server (Windows + Linux)
- [What Storm Changes](docs/what-storm-changes.md) — performance, behavioral overrides, bug fixes, mod-loader extensions
- [Server Configuration](docs/server-configuration.md) — system properties and a production launcher example
- [HTTP API](docs/http-api.md) — runtime tuning endpoints and developer hot-reload (Lua / Java)
- [Prometheus Metrics](docs/metrics.md) — exposing metrics and adding new ones from mods
- [Mod Author Guide](docs/mod-author-guide.md) — `ZomboidMod` entry point, annotation surfaces, Lua API, server commands
