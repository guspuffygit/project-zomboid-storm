# Storm Mod Loader

[![License](https://img.shields.io/github/license/pzstorm/storm?logo=gnu)](https://www.gnu.org/licenses/) 
[![Discord](https://img.shields.io/discord/823907021178798150?color=7289DA&label=discord&logo=discord&logoColor=white)](https://discord.gg/ZCmg9VsvSW)

Storm Mod Loader is a Java modding framework for Project Zomboid.

Successor to the original abandoned [Storm](https://github.com/pzstorm/storm)

## Installation

1. Subscribe to [Storm Mod Loader](https://steamcommunity.com/sharedfiles/filedetails/?id=3670772371) in the Steam Workshop.
2. Right click Project Zomboid in Steam Library, click Properties
3. In General, under Launch Options, copy and paste the line below into the input

#### Windows
```text
-pzexeconfig "../../workshop/content/108600/3670772371/mods/storm/storm-windows.json"
```

#### Linux
```text
-pzexeconfig "../../workshop/content/108600/3670772371/mods/storm/storm-linux.json"
```

When you start the game, the main screen menu should show the Storm version in the right bottom of the screen.

#### Mac

1. This works on Mac I just need to get my Mac out and try  it for the instructions. It requires editing Info.plist

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

Linux:
```
./gradlew clean spotlessApply installBootstrap installStorm publishToMavenLocal processGameConfig
```

Windows:
```
.\gradlew.bat clean spotlessApply installBootstrap installStorm publishToMavenLocal processGameConfig
```

2. Right click Project Zomboid in Steam Library, click Properties
3. In General, under Launch Options, copy and paste the line below into the input

```text
-pzexeconfig "storm-local.json"
```
