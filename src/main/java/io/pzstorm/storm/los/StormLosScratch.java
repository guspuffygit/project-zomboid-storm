package io.pzstorm.storm.los;

import zombie.iso.Vector2;

/**
 * Per-thread replacements for {@code IsoGridSquare.tempo} / {@code tempo2}, the two {@code static
 * final Vector2} scratch instances used inside {@code IsoGridSquare.CalcVisibility}'s server path.
 *
 * <p>When several LOS workers run {@code CalcVisibility} concurrently they would otherwise clobber
 * each other's shared {@code tempo}/{@code tempo2}, corrupting the dot-product/visibility math. The
 * server-only {@code IsoGridSquareLosParallelPatch} redirects every {@code getstatic} read of those
 * two fields to {@link #tempo()} / {@link #tempo2()} via Byte Buddy {@code MemberSubstitution}, so
 * each worker thread gets its own {@link Vector2}.
 *
 * <p>The fields are {@code final} (only ever read, never reassigned outside {@code <clinit>}), so a
 * read-redirect fully covers them. Single-threaded ({@code threads=1}) behaviour is unchanged: one
 * thread sees one stable instance, exactly like the original static.
 */
public final class StormLosScratch {

    private static final ThreadLocal<Vector2> TEMPO = ThreadLocal.withInitial(Vector2::new);
    private static final ThreadLocal<Vector2> TEMPO2 = ThreadLocal.withInitial(Vector2::new);

    private StormLosScratch() {}

    /** Replacement for reads of {@code IsoGridSquare.tempo}. */
    public static Vector2 tempo() {
        return TEMPO.get();
    }

    /** Replacement for reads of {@code IsoGridSquare.tempo2}. */
    public static Vector2 tempo2() {
        return TEMPO2.get();
    }
}
