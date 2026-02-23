function stormLoaderVerificationCheck()
    if Storm then
        print("Storm is loaded successfully")
    else
        print("================================================================================")
        print("  STORM BOOTSTRAP JAR IS NOT LOADED")
        print("================================================================================")
        print("")
        print("  The Storm mod requires the bootstrap jar to be added to your server startup.")
        print("  You must modify your StartServer64.bat (Windows) or start-server.sh (Linux).")
        print("")
        print("  REQUIRED CHANGES:")
        print("")
        print("  1. Add the storm-bootstrap.jar to PZ_CLASSPATH (BEFORE the other jars):")
        print("")
        print("     Workshop location:")
        print("       ../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar")
        print("")
        print("     Example PZ_CLASSPATH line:")
        print("       SET PZ_CLASSPATH=../../workshop/content/108600/3670772371/mods/storm/bootstrap/storm-bootstrap.jar;java/;java/projectzomboid.jar")
        print("")
        print("  2. Add these JVM flags:")
        print("       -Dstorm.server=true")
        print("")
        print("  3. Change the main class from:")
        print("       zombie.network.GameServer")
        print("     To:")
        print("       io.pzstorm.storm.StormBootstrapper")
        print("")
        print("================================================================================")
        getCore():quitToDesktop()
    end
end

Events.OnGameBoot.Add(stormLoaderVerificationCheck)


print('GOODBYE')
owijefoiwjeofij
getCore():quitToDesktop()
local function ServerPinged(clientAddress, numClients)
    -- your code here
end

Events.ServerPinged.Add(ServerPinged)