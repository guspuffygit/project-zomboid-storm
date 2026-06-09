# Storm Mod Loader

[![Maven Central](https://img.shields.io/maven-central/v/com.sentientsimulations/project-zomboid-storm)](https://central.sonatype.com/artifact/com.sentientsimulations/project-zomboid-storm)
[![License](https://img.shields.io/github/license/guspuffygit/project-zomboid-storm?logo=gnu)](https://www.gnu.org/licenses/)
[![Discord](https://img.shields.io/discord/823907021178798150?color=7289DA&label=discord&logo=discord&logoColor=white)](https://discord.gg/ZCmg9VsvSW)

Storm Mod Loader pairs server-side Java mods with Lua client mods to enable functionality beyond what Lua-only mods can do. Vanilla clients connect to a Java-modded server without the need to setup Java modding locally. Mods are distributed through the normal Steam Workshop.

Successor to the original abandoned [Storm](https://github.com/pzstorm/storm).

## Quickstart

See [Installation](docs/installation.md) for dedicated-server setup (Workshop or local-build) on Windows and Linux, and for the local-development workflow when iterating on Storm or a Storm-based mod.

## What Storm Does

Storm rewrites a chunk of the dedicated server's bytecode at load time to:

- **Patch vanilla bugs** that affect multiplayer (cross-player action cancels, zombie ID collisions, whisper case-sensitivity, …)
- **Lift hardcoded server limits** (configurable tick rate, parallel LOS pipeline, packet rate limit removed, raised zombie cull cap)
- **Surface event hooks to Java mods** (packet receipt, chat, ~190 Lua event bridges)
- **Extend the mod loader** with annotation-driven HTTP endpoints, server commands, and event handlers

See [What Storm Changes](docs/what-storm-changes.md) for the full list.

## Documentation

- [Installation](docs/installation.md) — dedicated server install (Workshop or local build, Windows + Linux) and local development workflow
- [What Storm Changes](docs/what-storm-changes.md) — performance, behavioral overrides, bug fixes, mod-loader extensions
- [Server Configuration](docs/server-configuration.md) — system properties and a production launcher example
- [HTTP API](docs/http-api.md) — runtime tuning endpoints and developer hot-reload (Lua / Java)
- [Prometheus Metrics](docs/metrics.md) — exposing metrics and adding new ones from mods
- [Mod Author Guide](docs/mod-author-guide.md) — `ZomboidMod` entry point, annotation surfaces, Lua API, server commands
