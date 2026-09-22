package audit;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tighter version of RaceTest: every reference to the shared collection is
 * declared and used as RaceList (never List/Collection/ArrayList), so no
 * *other* call site in this program is independently tracked by API.txt.
 * add(id + "-" + i) and shared.size() below are both INVOKEVIRTUAL calls
 * on owner "audit/RaceList" -- not a JDK type, not in API.txt, never
 * rewritten. The *only* tracked bytecode instruction anywhere in this run
 * is the INVOKESPECIAL super.add(o) inside RaceList.add() itself.
 *
 * This isolates the professor's question precisely: with that one call
 * site skipped (the INVOKESPECIAL fix), is there *any* remaining
 * instrumented access left for TSVD4J to build a conflicting pair from?
 * If not, a real race that only becomes reachable through a
 * super.<trackedMethod>() call is now undetectable by construction --
 * not because the race is subtle, but because every path to see it was
 * the one path the fix had to give up.
 */
public class RaceTest2 {
    static final int THREADS = 8;
    static final int ADDS_PER_THREAD = 2000;

    public static void main(String[] args) throws InterruptedException {
        final RaceList shared = new RaceList();
        final AtomicInteger exceptions = new AtomicInteger(0);
        Thread[] threads = new Thread[THREADS];

        for (int t = 0; t < THREADS; t++) {
            final int id = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < ADDS_PER_THREAD; i++) {
                    try {
                        shared.add(id + "-" + i);
                    } catch (RuntimeException e) {
                        exceptions.incrementAndGet();
                    }
                }
            });
        }

        long start = System.currentTimeMillis();
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        long elapsed = System.currentTimeMillis() - start;

        int expected = THREADS * ADDS_PER_THREAD;
        int actual = shared.size();
        System.out.println("Expected size: " + expected);
        System.out.println("Actual size:   " + actual);
        System.out.println("Exceptions thrown by add(): " + exceptions.get());
        System.out.println("Elapsed ms: " + elapsed);
        if (actual != expected || exceptions.get() > 0) {
            System.out.println("RACE CONFIRMED: unsynchronized concurrent super.add() corrupted RaceList.");
        } else {
            System.out.println("No corruption observed this run (races are timing-dependent -- rerun if needed).");
        }
    }
}
