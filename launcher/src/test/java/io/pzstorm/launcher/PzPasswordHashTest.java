package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

/**
 * The stored form must match what the game produces ({@code
 * PZcrypt.hash(ServerWorldDatabase.encrypt(password))}) byte-for-byte: the server compares it with
 * {@code String.equals}, so any deviation is an "incorrect password" at login.
 */
class PzPasswordHashTest {

    @Test
    void matchesTheGamesOwnDerivation() {
        // the game pipeline, spelled out: BCrypt over the lowercase MD5 hex with the fixed salt
        String md5OfSecret = "5ebe2294ecd0e0f08eab7690d2a6ee69"; // md5("secret")
        String expected = BCrypt.hashpw(md5OfSecret, PzPasswordHash.GAME_SALT);

        assertEquals(expected, PzPasswordHash.hash("secret", null));
    }

    @Test
    void emptyStaysEmptyLikePzcrypt() {
        assertEquals("", PzPasswordHash.hash("", null));
    }

    @Test
    void recognizesOnlyItsOwnOutput() {
        String hashed = PzPasswordHash.hash("secret", null);
        assertTrue(PzPasswordHash.isHashed(hashed));
        assertEquals(60, hashed.length());

        assertFalse(PzPasswordHash.isHashed(""));
        assertFalse(PzPasswordHash.isHashed("secret"));
        assertFalse(
                PzPasswordHash.isHashed(
                        "$2a$12$somethingElseEntirely.padding.to.sixty.chars.long.."));
    }

    @Test
    void isDeterministicAndInputSensitive() {
        assertEquals(PzPasswordHash.hash("secret", null), PzPasswordHash.hash("secret", null));
        assertFalse(
                PzPasswordHash.hash("secret", null).equals(PzPasswordHash.hash("Secret", null)));
    }
}
