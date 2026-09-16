# Minimal reproducer: TSVD4J instruments `super.add()` incorrectly

Applies to `master`, unmodified -- no changes to TSVD4J itself are needed.
Only two new files (`audit/MyList.java`, `audit/Main.java`, both included
here) are added *alongside* an unmodified checkout.

## The bug, in one paragraph

`ClassTracer.visitMethodInsn()` rewrites any call whose owner+name+descriptor
matches `API.txt`, regardless of the JVM opcode. `super.add(o)` compiles to
`INVOKESPECIAL` -- the opcode that means "call this exact method on this
exact class, skipping virtual dispatch," which is the entire reason
`super.foo()` exists. TSVD4J rewrites it the same way it would rewrite a
normal call, into a static call to `Proxy.add(receiver, arg, ...)`.
`Proxy.add()` then calls `list.add(obj)` -- an *ordinary virtual* call. If
the receiver's runtime type overrides `add()` (as any subclass calling
`super.add()` necessarily does), that virtual call dispatches straight back
into the override, which calls `super.add()` again, which gets rewritten
again. Infinite recursion, `StackOverflowError`.

## Files here

```
audit/MyList.java   -- 3-line ArrayList subclass: add() calls super.add(o)
audit/Main.java     -- calls new MyList().add("x") once
evidence/
  1-before-instrumentation.javap.txt   -- super.add(o) as compiled: INVOKESPECIAL
  2-after-instrumentation.javap.txt    -- same call, after TSVD4J: INVOKESTATIC Proxy.add(...)
  3-stackoverflow-crash.log            -- the live crash, captured on unmodified master
```

## Reproduce it yourself

```bash
git clone git@github.com:UT-SE-Research/TSVD4J.git
cd TSVD4J
mvn -q -pl tsvd4j-core -am install -DskipTests

# drop this package in anywhere on the classpath, e.g.:
mkdir -p /tmp/repro/audit
cp <this-folder>/audit/*.java /tmp/repro/audit/
cd /tmp/repro
javac --release 8 -d build audit/MyList.java audit/Main.java

# without the agent: works fine
java -cp build audit.Main
# -> OK: list = [x]

# with the agent: StackOverflowError
java -Xss136k \
     -javaagent:"$HOME/.m2/repository/edu/utexas/ece/tsvd4j-core/0.1-SNAPSHOT/tsvd4j-core-0.1-SNAPSHOT.jar" \
     -cp build audit.Main
```

### About the `-Xss136k` flag

The recursion is unconditionally infinite either way -- this flag doesn't
change *whether* it crashes, only *how many frames* it takes to physically
exhaust the stack, which changes how long that takes to happen. It's
included here because it matters in practice: at the JVM's normal default
stack size, this run is extremely slow to visibly crash on unmodified
`master` specifically -- independent of this bug, `master`'s own
interception bookkeeping (`Utility.onCall`) gets more expensive as more
calls accumulate, so reaching stack-overflow depth at a larger stack size
takes far more than proportionally longer. `-Xss136k` (close to the JVM's
minimum) keeps the number of frames needed small, so the same,
deterministic crash shows up in well under a second instead of an
unpredictable wait. Omit it and there's still a crash -- just not a quick
or reliably patience-testing one.

## What the evidence shows

**Before** (`evidence/1-before-instrumentation.javap.txt`), `super.add(o)`:
```
2: invokespecial #7   // Method java/util/ArrayList.add:(Ljava/lang/Object;)Z
```

**After** (`evidence/2-after-instrumentation.javap.txt`), same call:
```
8: invokestatic #24  // Method edu/utexas/ece/tsvd4j/agent/Proxy.add:
                      //   (Ljava/util/ArrayList;Ljava/lang/Object;ILjava/lang/String;Ljava/lang/String;)Z
```

**The crash** (`evidence/3-stackoverflow-crash.log`), captured on a clean
`master` build, no other changes:
```
Exception in thread "main" java.lang.StackOverflowError
	...
	at edu.utexas.ece.tsvd4j.agent.Proxy.add(Proxy.java:360)
	at audit.MyList.add(MyList.java:28)
	at edu.utexas.ece.tsvd4j.agent.Proxy.add(Proxy.java:361)
	at audit.MyList.add(MyList.java:28)
	... (repeats until the stack is exhausted)
```
