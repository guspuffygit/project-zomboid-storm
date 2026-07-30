@echo off
setlocal
rem Storm Launcher — pre-game UI for joining servers and syncing java mods.
rem When this script lives inside the Steam workshop item
rem (steamapps\workshop\content\108600\<id>\mods\storm\launcher\) the game install
rem sits seven directories up in the same Steam library; use its bundled JRE.
set "PZ=%~dp0..\..\..\..\..\..\..\common\ProjectZomboid"
if exist "%PZ%\jre64\bin\javaw.exe" (
  start "Storm Launcher" "%PZ%\jre64\bin\javaw.exe" -jar "%~dp0storm-launcher.jar" %*
  exit /b
)
start "Storm Launcher" javaw -jar "%~dp0storm-launcher.jar" %*
