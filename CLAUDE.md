Guidance for Claude Code working in this repo.

Client-side Java policy (decided 2026-07-30; replaces the old absolute ban). Client-side Java is allowed: Storm-core code that runs on the client JVM, and — when cheaper tiers can't do the job — new StormClassTransformer patches on classes that execute on the client. The Storm Launcher ships Storm to players, so client JVMs are expected to run Storm; vanilla-client compatibility is a design consideration, not a hard constraint.

Discipline still applies. Prefer the least invasive mechanism, in this order: server-side change → client Lua under media/lua/client/ → Storm-core client Java on existing surfaces (LuaEventManagerPatch's bridge delivers every Lua event to @SubscribeEvent handlers; LuaCompiler.loadstring + LuaManager.caller.pcall runs Lua from Java — see io.pzstorm.storm.client.LauncherAutoJoin for the pattern) → new client bytecode patch. A new client patch needs a reason the cheaper tiers can't cover, must fail soft (a broken patch logs and degrades; it never takes the client down), and adds re-validation work on every game update — say so in the PR/commit.

Existing client-side patches: MainScreenStatePatch, UIWorldMapPatch, LuaEventManagerPatch, TISLogoStatePatch, PacketReceivedPatch, ChatManagerPatch, LuaExposerDumpPatch, LuaManagerPatch, DebugLogPatch, ThreadPatch, RequestDataOverTcpPatch, PlayerProfileOverTcpPatch, ChunkRequestOverTcpPatch, WorldStreamerChunkTcpPatch.

Transformer gating (server-only patches). Gate ZomboidMod.getClassTransformers() on StormEnv.isStormServer(), not GameServer.server. GameServer.server is false at collectTransformers() time and silently drops every patch. See docs/mod-author-guide.md for the full pattern.

Source-only rule — never look in jars. Every Java class Storm and its consumer mods reference is available as .java source on disk. Always search .java files — never unzip, jar -xf, or read inside .jar files (including ~/.m2/, Gradle caches, build/zomboid-classpath/). If a find for .java returns nothing, the query is wrong; fix the query.

find tip: use -name '*.java' for basename, -path '*/storm/*' for directory — don't combine them as *storm*X*.java (different path components, won't match).

Commands you can't guess. Build + install Storm into the local workshop dir: ./gradlew clean spotlessApply installStorm. Run local dedicated server: ./gradlew runProjectZomboidServer. Publish storm.jar to the CDN so bootstraps pick it up without a workshop publish (bump stormVersion first): ./gradlew deployStormJar; same channel as :launcher:deployLauncher — see docs/installation.md "Storm core self-update".

installStorm fails with "Permission denied" on agentlib.dll / storm.jar while a client or dedicated server is running (the JVM memory-maps those files). Stop them first.

Versions and Steam Workshop IDs come from gradle.properties. Maven coordinates: com.sentientsimulations:project-zomboid-storm:<pzVersion>_<stormVersion>.

Reference. Architecture (bootstrap chain, event system, mod loading, mod entry point): docs/mod-author-guide.md. JVM flags, sandbox options: docs/server-configuration.md. What Storm patches in PZ (behavior, perf, bug fixes): docs/what-storm-changes.md. HTTP endpoints: docs/http-api.md. Prometheus metrics (adding new ones): docs/metrics.md. Installation paths (Workshop, dedicated server, local dev): docs/installation.md. Storm Launcher (pre-game UI, client mod sync, launcher/ subproject — no PZ classes allowed in it): docs/launcher.md.

Metadata. To disable metadata analytics, add -DDISABLE_ANALYTICS=true.
