package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivacyPolicyTest {

    @TempDir Path tmp;

    @Test
    void bakedDocumentLoadsWithAVersionLine() {
        PrivacyPolicy policy = PrivacyPolicy.current();
        assertFalse(policy.text().isEmpty());
        assertNotEquals("unversioned", policy.version());
        assertEquals(64, policy.hash().length());
    }

    @Test
    void identityIsTheContentHashSoAnyEditReprompts() {
        PrivacyPolicy v1 = PrivacyPolicy.of("Terms\nVersion: 1\n\nbody");
        PrivacyPolicy sameBytesCrlf = PrivacyPolicy.of("Terms\r\nVersion: 1\r\n\r\nbody\r\n");
        PrivacyPolicy typoFix = PrivacyPolicy.of("Terms\nVersion: 1\n\nbody.");

        LauncherConfig config = new LauncherConfig();
        assertFalse(PrivacyPolicy.everAccepted(config));
        assertFalse(v1.isAcceptedBy(config));

        v1.recordAcceptance(config);
        assertTrue(v1.isAcceptedBy(config));
        assertTrue(sameBytesCrlf.isAcceptedBy(config));
        assertFalse(typoFix.isAcceptedBy(config), "an unbumped edit must still re-prompt");
        assertTrue(PrivacyPolicy.everAccepted(config));
        assertEquals("1", config.acceptedTermsVersion);
        assertFalse(config.acceptedTermsAt.isEmpty());
    }

    @Test
    void acceptanceSurvivesConfigRoundtrip() throws IOException {
        PrivacyPolicy policy = PrivacyPolicy.current();
        LauncherConfig config = new LauncherConfig();
        policy.recordAcceptance(config);
        Path file = tmp.resolve("launcher.json");
        config.save(file);

        LauncherConfig loaded = LauncherConfig.load(file);
        assertTrue(policy.isAcceptedBy(loaded));
        assertEquals(policy.version(), loaded.acceptedTermsVersion);
        assertEquals(config.acceptedTermsAt, loaded.acceptedTermsAt);
    }

    @Test
    void versionHeaderIsOptional() {
        assertEquals("unversioned", PrivacyPolicy.parseVersion("no header here"));
        assertEquals("2026-08-31", PrivacyPolicy.parseVersion("Title\n  Version:  2026-08-31 \n"));
    }

    @Test
    void gateAcceptsPromptsAndRecordsOrDeclinesWithoutSaving() {
        System.setProperty("storm.launcher.zomboidDir", tmp.toString());
        try {
            PrivacyPolicy policy = PrivacyPolicy.current();
            LauncherConfig config = new LauncherConfig();
            List<Boolean> promptedAsUpdated = new ArrayList<>();

            assertFalse(
                    LauncherMain.ensureTermsAccepted(
                            config,
                            (p, updated) -> {
                                promptedAsUpdated.add(updated);
                                return false;
                            }));
            assertFalse(policy.isAcceptedBy(config));
            assertEquals(List.of(false), promptedAsUpdated);

            assertTrue(
                    LauncherMain.ensureTermsAccepted(
                            config,
                            (p, updated) -> {
                                promptedAsUpdated.add(updated);
                                return true;
                            }));
            assertTrue(policy.isAcceptedBy(config));
            assertTrue(policy.isAcceptedBy(LauncherConfig.load(LauncherPaths.configFile())));

            // already accepted: no prompt at all
            assertTrue(
                    LauncherMain.ensureTermsAccepted(
                            config,
                            (p, updated) -> {
                                throw new AssertionError("prompted although accepted");
                            }));

            // a changed document re-prompts with the "updated" wording
            config.acceptedTermsHash = "stale";
            assertTrue(
                    LauncherMain.ensureTermsAccepted(
                            config,
                            (p, updated) -> {
                                promptedAsUpdated.add(updated);
                                return true;
                            }));
            assertEquals(List.of(false, false, true), promptedAsUpdated);
        } finally {
            System.clearProperty("storm.launcher.zomboidDir");
        }
    }
}
