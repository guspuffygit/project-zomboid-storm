package io.pzstorm.launcher;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Produces the password form the game keeps in its saved-servers database and sends on login.
 *
 * <p>The game never stores or transmits an account password in the clear. What lands in {@code
 * ServerListSteam.db} — and what {@code ServerWorldDatabase.authClient} compares byte-for-byte on
 * the server — is {@code BCrypt(md5hex(password))} with a fixed salt: {@code
 * zombie.core.secure.PZcrypt.hash(zombie.network.ServerWorldDatabase.encrypt(password))}. Vanilla's
 * saved-credentials join sends the stored value as-is ({@code doHash=false} in {@code
 * MultiplayerUI:connectToServer}); only a freshly typed password goes through the hashing connect
 * path. The launcher therefore keeps every account password in this stored form: anything else
 * written to the database or handed to the game would fail auth as "incorrect password".
 *
 * <p>The plaintext is unrecoverable from the stored form, which is the point — and why the launcher
 * hashes at the moment of input instead of at the moment of use.
 *
 * <p>BCrypt is borrowed at runtime from the game's own {@code projectzomboid.jar} through an
 * isolated classloader, exactly like the SQLite driver in {@link VanillaServerDb}; {@code
 * org.mindrot.jbcrypt} is not a game class, so the no-PZ-classes rule holds. The classpath is tried
 * first (tests ship the library that way).
 */
public final class PzPasswordHash {

    /** PZcrypt's fixed salt: every game-form hash is exactly 60 chars starting with it. */
    static final String GAME_SALT = "$2a$12$O/BFHoDFPrfFaNPAACmWpu";

    private static final int BCRYPT_LENGTH = 60;

    private PzPasswordHash() {}

    /**
     * Whether the value is already in the game's stored form. A real password could only be
     * mistaken for one by being exactly 60 chars and starting with the game's own 29-char salt.
     */
    static boolean isHashed(String value) {
        return value.length() == BCRYPT_LENGTH && value.startsWith(GAME_SALT);
    }

    /**
     * The game's stored form of a plaintext password: {@code BCrypt(md5hex(plaintext))} with the
     * fixed salt. Empty stays empty ({@code PZcrypt.hash} does the same, so "no password" survives
     * the round-trip). Returns null when BCrypt is unavailable — no game jar and none on the
     * classpath — so callers can refuse to store a value the game cannot use.
     */
    static String hash(String plaintext, Path gameJar) {
        if (plaintext.isEmpty()) {
            return "";
        }
        try {
            return bcrypt(Class.forName("org.mindrot.jbcrypt.BCrypt"), md5Hex(plaintext));
        } catch (ReflectiveOperationException ignored) {
            // expected in production — borrow the game's copy instead
        }
        if (gameJar == null || !Files.isRegularFile(gameJar)) {
            return null;
        }
        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {gameJar.toUri().toURL()},
                        PzPasswordHash.class.getClassLoader())) {
            return bcrypt(loader.loadClass("org.mindrot.jbcrypt.BCrypt"), md5Hex(plaintext));
        } catch (ReflectiveOperationException | IOException e) {
            Log.warn("Could not load BCrypt from " + gameJar + ": " + e.getMessage());
            return null;
        }
    }

    private static String bcrypt(Class<?> bcryptClass, String md5Hex)
            throws ReflectiveOperationException {
        Method hashpw = bcryptClass.getMethod("hashpw", String.class, String.class);
        return (String) hashpw.invoke(null, md5Hex, GAME_SALT);
    }

    private static String md5Hex(String plaintext) {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 missing from the JRE", e);
        }
        // getBytes() without a charset mirrors ServerWorldDatabase.encrypt: the launcher runs on
        // the same bundled JRE as the game, so the default charset — and the digest — match
        byte[] digest = md5.digest(plaintext.getBytes());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
