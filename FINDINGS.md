# Question 2: does skipping INVOKESPECIAL cost a real detection?

Follow-up to Question 1 (see the `invokespecial-minimal-repro` branch):
the obvious fix there is to have `ClassTracer.visitMethodInsn()` skip
instrumenting any `INVOKESPECIAL` call. This asks whether that skip can
cause TSVD4J to miss a real, live concurrency bug -- one whose only
racing access happens through a `super.<method>()` call.

## Files here

```
audit/RaceList.java   -- ArrayList subclass, add() calls super.add(o); no synchronization
audit/RaceTest2.java  -- 8 threads add() concurrently on one shared RaceList, no external lock
evidence/
  4-race-unfixed.stdout.log   -- run against the unmodified (current) agent
  5-race-fixed.stdout.log     -- run against the agent with INVOKESPECIAL skipped
```

`RaceList.add()` does nothing but `return super.add(o);` -- so the *only*
tracked bytecode instruction anywhere in this program is that one
`INVOKESPECIAL` call. Every reference to the shared collection in
`RaceTest2` is declared and used as `RaceList` itself, never
`List`/`Collection`/`ArrayList`, so no *other* call site is independently
tracked by `API.txt` either -- this isolates the question precisely, with
no other instrumented access left over to produce a false confidence.

`ArrayList.add()` is well known to not be thread-safe: concurrent,
unsynchronized calls race on the shared backing array and size field. This
is a real, textbook thread-safety violation, not a synthetic pattern built
to satisfy the instrumenter.

## The one-line fix under test

```diff
- if (listAPI.contains(combined_name)) {
+ if (listAPI.contains(combined_name) && opcode != Opcodes.INVOKESPECIAL) {
```
in `ClassTracer.visitMethodInsn()` (`tsvd4j-core/src/main/java/edu/utexas/ece/tsvd4j/agent/ClassTracer.java`).

## Reproduce it yourself

```bash
git clone git@github.com:UT-SE-Research/TSVD4J.git
cd TSVD4J

mkdir -p /tmp/repro2/audit && cp <this-folder>/audit/RaceList.java /tmp/repro2/audit/ && cp <this-folder>/audit/RaceTest2.java /tmp/repro2/audit/
cd /tmp/repro2 && javac --release 8 -d build audit/RaceList.java audit/RaceTest2.java

# 1. unfixed: build and run against unmodified master.
# -Xss512k matters here: at the JVM's default stack size, master's own
# interception bookkeeping gets slower as calls accumulate (same effect
# noted in Question 1), so this run can take minutes instead of ~1s to
# visibly crash. Omit it and you'll still get the same result, just slowly.
cd TSVD4J && mvn -q -pl tsvd4j-core -am install -DskipTests && cd /tmp/repro2
java -Xss512k -javaagent:"$HOME/.m2/repository/edu/utexas/ece/tsvd4j-core/0.1-SNAPSHOT/tsvd4j-core-0.1-SNAPSHOT.jar" -cp build audit.RaceTest2

# 2. fixed: apply the one-line diff above to ClassTracer.java, rebuild, rerun.
# No -Xss needed -- this run no longer recurses, so it never gets deep.
cd TSVD4J
# (edit ClassTracer.java as shown above)
mvn -q -pl tsvd4j-core -am install -DskipTests
cd /tmp/repro2
java -javaagent:"$HOME/.m2/repository/edu/utexas/ece/tsvd4j-core/0.1-SNAPSHOT/tsvd4j-core-0.1-SNAPSHOT.jar" -cp build audit.RaceTest2
```

Both `ADDS_PER_THREAD` and `THREADS` are fixed in `RaceTest2.java`, but the
race itself is timing-dependent -- rerunning will not reproduce the exact
same `Actual size` or `Thread Count` every time, only the same *pattern*
(unfixed: crashes, one crash-artifact pair; fixed: no crash, zero pairs).

## What the evidence shows

| Agent | Crash? | Actual size (of 16000) | Conflicting pairs | Thread Count |
|---|---|---|---|---|
| unfixed (`evidence/4-race-unfixed.stdout.log`) | Yes -- `StackOverflowError` in every thread | 0 (every add failed) | 1 (`RaceList\|add\|17` self-pair -- a crash artifact, not a real detection) | 16418\* |
| **fixed** (`evidence/5-race-fixed.stdout.log`) | No | 7051 (race still real, still corrupts the list) | **0** | **0** |

\* `Thread Count` in the unfixed run is noisy on `master` specifically, for
a reason unrelated to this question: `Helper.threadCountList` is a plain
`ArrayList<String>` deduplicated by checking `.contains()` against a boxed
`Long`, which can never match a `String` element -- so the "already seen"
guard never fires and every intercepted call adds an entry. It's a real,
separate bug, just not the one this question is about; it doesn't
change the fixed row, since `0` means zero tracked calls were ever
registered, independent of that guard.

**Answer:** yes, skipping `INVOKESPECIAL` costs a real detection. `Thread
Count = 0` in the fixed run means TSVD4J never registered a single tracked
call for this program -- not "found nothing suspicious," but structurally
blind, because the only place the tracked operation happens is the exact
call site the fix must skip. The race is exactly as severe as before
(here, 56% of adds silently lost) and is now invisible to TSVD4J by
construction, with no message or warning that anything was skipped.
