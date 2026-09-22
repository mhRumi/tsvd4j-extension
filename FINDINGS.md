# Question 4: is there a better solution than simply excluding INVOKESPECIAL?

Follow-ups so far (see the other three `invokespecial-*` branches): the
recursion bug is real (Question 1), the obvious one-line fix for it --
skip instrumenting `INVOKESPECIAL` entirely -- trades away a real
detection (Question 2), and the exposure isn't rare in real code
(Question 3). This asks whether `INVOKESPECIAL` can be instrumented
*without* giving up either correctness or detection.

**Answer: yes.** The recursion and the detection loss turn out not to be
inherently coupled -- both were side effects of one specific
implementation choice (routing the tracked call through a virtual
dispatch on the receiver via `Proxy.<method>()`). Recording the access
without rewriting the call preserves both dispatch semantics and
detection.

## The idea

Instead of rewriting a tracked `INVOKESPECIAL owner.method(args)` into a
static call to `Proxy.<method>()` (an ordinary *virtual* call once inside
`Proxy`, which is what causes the recursion when the receiver's runtime
type overrides the method), this:

1. Wraps the method's real bytecode writer in an ASM
   `org.objectweb.asm.commons.LocalVariablesSorter` (already a
   `tsvd4j-core` dependency, just previously unused) so the visitor can
   safely borrow fresh local-variable slots without colliding with the
   method's own locals.
2. On a tracked `INVOKESPECIAL`, pops the call's arguments into those
   fresh locals, leaving just the receiver on the stack.
3. `DUP`s the receiver and calls a new side-effect-only tracking method
   (`TrackOnly.track()`, below) that does exactly what `Proxy.add()`
   does for bookkeeping (`Helper.createInstance` + `Utility.onCall`)
   *minus* the delegating call -- so it feeds the same
   conflict-detection machinery, not a parallel one.
4. Reloads the arguments in their original order and emits the
   **original, untouched `INVOKESPECIAL`** -- real, non-virtual
   dispatch, exactly as javac generated it.

## Files here

```
patch/ClassTracer.java        -- tsvd4j-core's ClassTracer.java with the fix applied
patch/ClassTracer.java.diff   -- the same change as a diff against master, for review
patch/TrackOnly.java          -- new file: the side-effect-only tracking method
audit/MyList.java, Main.java       -- Question 1's crash repro (used here as a regression check)
audit/RaceList.java, RaceTest2.java -- Question 2's isolated race probe
evidence/
  7-mylist-v2-run.log      -- MyList/Main run against the patched agent: no crash
  8-race2-v2.stdout.log    -- RaceTest2 run against the patched agent: no crash, real pair detected
```

Unlike the Question 4 proof of concept this is based on (which was built
against a separate branch's already-modified `ClassTracer.java` and
tracked through an external `audit.TrackOnly` test class swapped into a
repackaged jar), this version is a direct patch to unmodified `master`'s
actual `ClassTracer.java`, plus one new file inside `tsvd4j-core` itself
(`TrackOnly.java`) rather than a class living in the test program's own
classpath -- closer to what shipping this would actually look like.

## Reproduce it yourself

```bash
git clone git@github.com:UT-SE-Research/TSVD4J.git
cd TSVD4J

# apply the patch
cp <this-folder>/patch/ClassTracer.java tsvd4j-core/src/main/java/edu/utexas/ece/tsvd4j/agent/ClassTracer.java
cp <this-folder>/patch/TrackOnly.java tsvd4j-core/src/main/java/edu/utexas/ece/tsvd4j/agent/TrackOnly.java
mvn -q -pl tsvd4j-core -am install -DskipTests

mkdir -p /tmp/repro4/audit && cp <this-folder>/audit/*.java /tmp/repro4/audit/
cd /tmp/repro4 && javac --release 8 -d build audit/MyList.java audit/Main.java audit/RaceList.java audit/RaceTest2.java
JAR="$HOME/.m2/repository/edu/utexas/ece/tsvd4j-core/0.1-SNAPSHOT/tsvd4j-core-0.1-SNAPSHOT.jar"

# regression check: Question 1's crash case should no longer crash
java -javaagent:"$JAR" -cp build audit.Main
# -> OK: list = [x]

# Question 2's isolated race: should detect the real pair, no crash
java -javaagent:"$JAR" -cp build audit.RaceTest2
```

Note: this run is noticeably slower than the one-line-skip fix's -- on
this patch, every add() in `RaceTest2` goes through the full tracking
path, and `master`'s own interception bookkeeping (`Utility.onCall`) gets
more expensive as calls accumulate (the same effect Question 1 describes
for the crash case), so `RaceTest2` here took ~93s instead of the
skip-fix's ~7ms. That's an existing `master` performance characteristic
this patch exposes more of by tracking more calls, not something this
patch itself introduces.

## What the evidence shows

| Probe | Unfixed (current `master`) | Skip fix (Question 2) | **This fix** |
|---|---|---|---|
| Question 1 crash case (`MyList`/`Main`) | `StackOverflowError` | No crash | **No crash** (`evidence/7-mylist-v2-run.log`) |
| Question 2 race case (`RaceTest2`) | `StackOverflowError`, 1 crash-artifact pair | No crash, **0** pairs (blind) | **No crash, 1 real pair** (`evidence/8-race2-v2.stdout.log`: `audit/RaceList\|add\|17:audit/RaceList\|add\|17`) |

This fix is the only one of the three that avoids the crash *and* still
detects the real race, at the real line, with real dispatch semantics
preserved.

## What's still missing before this could ship

This is a proof of concept, not shipping code:

- It hardcodes `Operation.WRITE` in `TrackOnly.track()`. A real version
  needs the same per-method READ/WRITE lookup `Proxy.java` already does
  by method name -- not every tracked API call is a write.
- It hasn't been exercised against `itf=true` interface super-calls
  (Java 8+ default methods also compile to `INVOKESPECIAL`), though the
  same save/restore-locals technique should apply unchanged.
- It doesn't address the field-tracking side of `ClassTracer`
  (irrelevant here -- `INVOKESPECIAL` only applies to method calls).

## Recommendation

Replace the one-line `INVOKESPECIAL` skip with this approach rather than
ship the correctness/detection tradeoff Question 2 documented.
