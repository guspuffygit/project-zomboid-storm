package io.pzstorm.storm.bullet.trace;

/**
 * One difference between a recording and a replay (or between two traces).
 *
 * @param rootIndex index of the top-level call the difference is under (0-based, trace order)
 * @param seq sequence number of the recorded event concerned, -1 when there is none
 * @param path where in the call tree, e.g. {@code stepSimulation > upcall
 *     updatePhysicsForLevelIfNeeded > ToBullet}
 * @param kind short category, see {@link Kind}
 * @param method the native or upcall name the difference is counted against
 * @param call the recorded call with its arguments
 * @param detail expected vs actual, with hex bits for floats
 */
public record Divergence(
        long rootIndex,
        long seq,
        String path,
        Kind kind,
        String method,
        String call,
        String detail) {

    public enum Kind {
        /** A native's return value differs. */
        RETURN,
        /** An array / buffer argument holds different contents after the call. */
        ARG_OUT,
        /** One side threw, or both threw different exceptions. */
        EXCEPTION,
        /** The backend made an upcall the recording does not have at this point. */
        EXTRA_UPCALL,
        /** The recording has an upcall the backend did not make. */
        MISSING_UPCALL,
        /** Same upcall, different arguments. */
        UPCALL_ARGS,
        /** Same upcall, different result (trace-vs-trace only). */
        UPCALL_RESULT,
        /** Two traces call different natives or with different inputs (trace-vs-trace only). */
        CALL_MISMATCH,
        /** Two traces have a different number of calls (trace-vs-trace only). */
        STRUCTURE,
        /** The backend cannot perform the call (unknown or unimplemented native). */
        UNSUPPORTED
    }

    public String format() {
        return "root #"
                + rootIndex
                + (seq >= 0 ? " (event seq " + seq + ")" : "")
                + " "
                + kind
                + " in "
                + path
                + "\n    call:   "
                + call
                + "\n    detail: "
                + detail.replace("\n", "\n    ");
    }
}
