package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The watchdog's live loop reads game statics that only exist in a running client, so tests cover
 * the pure pieces: the queue-item describer that names a wedged asset task, and the stack helpers
 * the stall fingerprint is built from.
 */
class ClientLoadingWatchdogTest {

    /** Mirrors the shape of {@code FileSystemImpl.AsyncItem}: a private {@code task} field. */
    private static final class FakeAsyncItem {
        @SuppressWarnings("unused")
        private final Object task;

        FakeAsyncItem(Object task) {
            this.task = task;
        }
    }

    private static final class FakeModelTask {
        @SuppressWarnings("unused")
        private final int priority = 3;

        @SuppressWarnings("unused")
        private final String path = "media/models/violin.fbx";
    }

    private static final class BareTask {}

    @Test
    void describeTaskNamesClassAndFirstStringField() {
        String text = ClientLoadingWatchdog.describeTask(new FakeAsyncItem(new FakeModelTask()));

        assertEquals("FakeModelTask(media/models/violin.fbx)", text);
    }

    @Test
    void describeTaskWithoutStringFieldsIsJustTheClassName() {
        assertEquals(
                "BareTask", ClientLoadingWatchdog.describeTask(new FakeAsyncItem(new BareTask())));
    }

    @Test
    void describeTaskIsFailSoftOnUnexpectedShapes() {
        assertEquals("null", ClientLoadingWatchdog.describeTask(new FakeAsyncItem(null)));
        // no `task` field at all — must degrade to a note, never throw
        assertTrue(ClientLoadingWatchdog.describeTask(new Object()).startsWith("unreadable:"));
    }

    @Test
    void stackHelpersHandleEmptyAndPopulatedStacks() {
        assertEquals("?", ClientLoadingWatchdog.topFrame(new StackTraceElement[0]));
        assertEquals("", ClientLoadingWatchdog.stackString(new StackTraceElement[0]));

        StackTraceElement frame =
                new StackTraceElement("zombie.GameWindow", "run", "GameWindow.java", 100);
        StackTraceElement[] stack = {frame};
        assertEquals(frame.toString(), ClientLoadingWatchdog.topFrame(stack));
        assertEquals(frame + ";", ClientLoadingWatchdog.stackString(stack));
    }
}
