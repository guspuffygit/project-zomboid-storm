---
name: add-server-command
description: Add a new dedicated-server console command to Storm (a `CommandBase` subclass routed through `StormCommandRegistry`). Use when exposing a new admin/debug command callable from the server console or chat.
---

# Add a server command

`CommandBase.findCommandCls(String)` searches a static `childrenClasses` array for `@CommandName` annotations. Storm patches it (via `CommandBasePatch.FindCommandClsAdvice`) to also check mod commands registered in `StormCommandRegistry` when the base game returns no match. `CommandBase.getSubClasses()` is also patched (`GetSubClassesAdvice`) so mod commands appear in `help`.

## Pattern

1. Create a class extending `zombie.commands.CommandBase`:

   ```java
   @CommandName(name = "mycommand")
   @CommandHelp(helpText = "Description here.", shouldTranslated = false)
   @RequiredCapability(requiredCapability = Capability.DebugConsole)
   public class MyCommand extends CommandBase {
       public MyCommand(String username, Role userRole, String command, UdpConnection connection) {
           super(username, userRole, command, connection);
       }

       @Override
       protected String Command() {
           return "result text";
       }
   }
   ```

2. Register it. **For a mod**, return it from `ZomboidMod.getCommandClasses()`. **For Storm itself**, add to `StormCommandRegistry.collectCommands()`:

   ```java
   MOD_COMMANDS.add(MyCommand.class);
   ```

3. Build and test:

   ```bash
   ./gradlew clean spotlessApply installStorm publishToMavenLocal
   # run the server, then send the command
   ```

   For running the server, see the `run-pz-server` skill.

## Tips

- `@RequiredCapability` gates visibility. `Capability.DebugConsole` restricts to admins; pick a less-privileged one if regular players should see/use it.
- `shouldTranslated = false` skips the i18n lookup for `helpText`. Set `true` and add a translation entry only if you actually need localization.
- The string returned from `Command()` is what the caller sees back. Return a status line or short result; longer output should be logged.
