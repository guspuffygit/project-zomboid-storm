package io.pzstorm.storm.popman;

/**
 * Every value {@code MapCollisionData.init} and {@code updateGameState} pushed into the DLL through
 * {@code n_setGameState}, kept under the same keys. One instance is shared by the collision map and
 * the population, exactly as the loose native globals were.
 *
 * <p>Two separate flags stop zombies existing and they are not the same question: {@code
 * Core.bLastStand} is the Last Stand game mode, {@code World.ZombiesDisabled} is the sandbox
 * option. Collapsing them into one "no zombies" test loses a distinction the native kept.
 *
 * <p>The {@code double} and {@code float} overloads exist on the Java side but no key accepts them;
 * like the native they reject every key. {@code Core.GameMode}, {@code GameWindow.CacheDir}, {@code
 * GameWindow.SaveDir} and {@code PAUSED} are stored and never read, also like the native.
 */
public final class PopManGameState {

    /** {@code SandboxOptions.Distribution}; 2 selects the uniform distribution. */
    public static final int DISTRIBUTION_UNIFORM = 2;

    public boolean lastStand;
    public boolean zombiesDisabled;
    public boolean noSave;
    public boolean paused;
    public int distribution = 1;
    public int zombiesOption;
    public String gameMode = "";
    public String gameSaveWorld = "";
    public String cacheDir = "";
    public String gameModeCacheDir = "";
    public String saveDir = "";
    public String subdirChunkData = "";
    public String subdirZpop = "";

    public void setBoolean(String key, boolean value) {
        switch (key) {
            case "Core.bLastStand" -> lastStand = value;
            case "Core.noSave" -> noSave = value;
            case "PAUSED" -> paused = value;
            case "World.ZombiesDisabled" -> zombiesDisabled = value;
            default -> throw new IllegalArgumentException("unknown key " + key);
        }
    }

    public void setDouble(String key, double value) {
        throw new IllegalArgumentException("unknown key " + key);
    }

    public void setFloat(String key, float value) {
        throw new IllegalArgumentException("unknown key " + key);
    }

    public void setInt(String key, int value) {
        switch (key) {
            case "SandboxOptions.Distribution" -> distribution = value;
            case "SandboxOptions.Zombies" -> zombiesOption = value;
            default -> throw new IllegalArgumentException("unknown key " + key);
        }
    }

    public void setString(String key, String value) {
        String stored = value == null ? "" : value;
        switch (key) {
            case "Core.GameMode" -> gameMode = stored;
            case "Core.GameSaveWorld" -> gameSaveWorld = stored;
            case "GameWindow.CacheDir" -> cacheDir = stored;
            case "GameWindow.GameModeCacheDir" -> gameModeCacheDir = stored;
            case "GameWindow.SaveDir" -> saveDir = stored;
            case "SavefileNaming.SUBDIR_CHUNKDATA" -> subdirChunkData = stored;
            case "SavefileNaming.SUBDIR_ZPOP" -> subdirZpop = stored;
            default -> throw new IllegalArgumentException("unknown key " + key);
        }
    }

    public boolean isUniformDistribution() {
        return distribution == DISTRIBUTION_UNIFORM;
    }
}
