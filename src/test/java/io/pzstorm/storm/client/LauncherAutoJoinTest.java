package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherAutoJoinTest {

    @TempDir Path tmp;

    @AfterEach
    void tearDown() {
        System.clearProperty(LauncherAutoJoin.AUTOJOIN_FILE_PROPERTY);
    }

    private Path writeHandoff(Properties props) throws IOException {
        Path file = tmp.resolve("autojoin.properties");
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(writer, null);
        }
        return file;
    }

    private Properties fullHandoff() {
        Properties props = new Properties();
        props.setProperty("host", "play.example.org");
        props.setProperty("port", "16261");
        props.setProperty("username", "gus");
        // Properties escaping must survive characters that broke the old key=value format
        props.setProperty("password", "p=w: with spaces\\andé");
        props.setProperty("serverPassword", "spw");
        return props;
    }

    @Test
    void readsHandoffAndDeletesItImmediately() throws IOException {
        Path file = writeHandoff(fullHandoff());

        Properties read = LauncherAutoJoin.readAndDelete(file);

        assertEquals("play.example.org", read.getProperty("host"));
        assertEquals("16261", read.getProperty("port"));
        assertEquals("gus", read.getProperty("username"));
        assertEquals("p=w: with spaces\\andé", read.getProperty("password"));
        assertEquals("spw", read.getProperty("serverPassword"));
        assertFalse(Files.exists(file), "credentials must not survive the first read");
    }

    @Test
    void missingFileReturnsNull() throws IOException {
        assertNull(LauncherAutoJoin.readAndDelete(tmp.resolve("nope.properties")));
    }

    @Test
    void incompleteHandoffIsRejectedButStillDeleted() throws IOException {
        Properties missingUsername = fullHandoff();
        missingUsername.remove("username");
        Path file = writeHandoff(missingUsername);

        assertNull(LauncherAutoJoin.readAndDelete(file));
        assertFalse(Files.exists(file), "even unusable credentials must be deleted");
    }

    @Test
    void completenessRequiresHostPortAndUsername() {
        assertTrue(LauncherAutoJoin.isComplete(fullHandoff()));
        for (String required : new String[] {"host", "port", "username"}) {
            Properties props = fullHandoff();
            props.setProperty(required, "");
            assertFalse(LauncherAutoJoin.isComplete(props), required + " must be required");
        }
        Properties noPasswords = fullHandoff();
        noPasswords.remove("password");
        noPasswords.remove("serverPassword");
        assertTrue(LauncherAutoJoin.isComplete(noPasswords), "passwords are optional");
    }

    @Test
    void handoffPathComesFromTheSystemProperty() {
        assertNull(LauncherAutoJoin.handoffFile(), "no property, no auto-join");
        System.setProperty(LauncherAutoJoin.AUTOJOIN_FILE_PROPERTY, "");
        assertNull(LauncherAutoJoin.handoffFile(), "empty property means disabled");
        System.setProperty(
                LauncherAutoJoin.AUTOJOIN_FILE_PROPERTY, tmp.resolve("a.properties").toString());
        assertEquals(tmp.resolve("a.properties"), LauncherAutoJoin.handoffFile());
    }
}
