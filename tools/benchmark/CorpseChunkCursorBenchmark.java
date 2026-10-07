import io.pzstorm.storm.spatial.StormChunkIndex;
import io.pzstorm.storm.spatial.StormObjectList;
import java.util.Arrays;
import java.util.Random;

/** Isolated candidate traversal benchmark; does not measure game FPS or a full server tick. */
public final class CorpseChunkCursorBenchmark {
    private static volatile long sink;
    private static final int CAP = 25;
    private static final int QUERIES = 20_000;
    private record Candidate(int x, int y, int z, boolean sameBuilding) {}

    public static void main(String[] args) {
        for (int population : new int[] {0, 40, 500, 5_000, 20_000}) {
            run(population, true);
            run(population, false);
        }
    }

    private static void run(int population, boolean qualifying) {
        StormChunkIndex index = new StormChunkIndex();
        StormObjectList scratch = new StormObjectList(64);
        StormChunkIndex.Cursor cursor = index.newCursor();
        Random random = new Random(42);
        index.beginTick(1);
        for (int i = 0; i < population; i++) {
            int x = random.nextInt(25) - 12;
            int y = random.nextInt(25) - 12;
            Candidate candidate = new Candidate(x, y, qualifying ? 0 : 1, true);
            index.add(candidate, x, y, StormChunkIndex.TYPE_ZOMBIE);
        }
        index.endTick();
        int expected = copy(index, scratch);
        if (expected != walk(cursor)) throw new AssertionError("count differs");
        for (int warmup = 0; warmup < 5; warmup++) {
            timedCopy(index, scratch);
            timedWalk(cursor);
        }
        long[] copy = new long[9];
        long[] walk = new long[9];
        for (int sample = 0; sample < copy.length; sample++) {
            if ((sample & 1) == 0) {
                copy[sample] = timedCopy(index, scratch);
                walk[sample] = timedWalk(cursor);
            } else {
                walk[sample] = timedWalk(cursor);
                copy[sample] = timedCopy(index, scratch);
            }
        }
        Arrays.sort(copy);
        Arrays.sort(walk);
        System.out.printf("population=%d qualifying=%s result=%d copy_ns=%.1f cursor_ns=%.1f ratio=%.3f%n",
                population, qualifying, expected, copy[4] / (double) QUERIES,
                walk[4] / (double) QUERIES, walk[4] / (double) copy[4]);
    }

    private static long timedCopy(StormChunkIndex index, StormObjectList scratch) {
        long start = System.nanoTime();
        long total = 0;
        for (int i = 0; i < QUERIES; i++) total += copy(index, scratch);
        sink = total;
        return System.nanoTime() - start;
    }

    private static long timedWalk(StormChunkIndex.Cursor cursor) {
        long start = System.nanoTime();
        long total = 0;
        for (int i = 0; i < QUERIES; i++) total += walk(cursor);
        sink = total;
        return System.nanoTime() - start;
    }

    private static int copy(StormChunkIndex index, StormObjectList scratch) {
        scratch.clear();
        index.collectChunkRect(-3, -3, 2, 2, StormChunkIndex.MASK_ZOMBIE, scratch);
        int count = 0;
        for (int i = 0; i < scratch.size() && count < CAP; i++) {
            if (qualifies((Candidate) scratch.get(i))) count++;
        }
        scratch.clear();
        return count;
    }

    private static int walk(StormChunkIndex.Cursor cursor) {
        cursor.beginChunkRect(-3, -3, 2, 2, StormChunkIndex.MASK_ZOMBIE);
        int count = 0;
        try {
            while (count < CAP) {
                Candidate candidate = (Candidate) cursor.next();
                if (candidate == null) break;
                if (qualifies(candidate)) count++;
            }
            return count;
        } finally {
            cursor.end();
        }
    }

    private static boolean qualifies(Candidate candidate) {
        return candidate.z == 0 && candidate.x >= -12 && candidate.x <= 12
                && candidate.y >= -12 && candidate.y <= 12 && candidate.sameBuilding;
    }
}
