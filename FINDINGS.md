# Question 3: how common is this in real code?

The recursion bug (and the fix's blind spot from Question 2) only matters
in practice if real code actually reaches a tracked API through
`super.<method>()`. `audit/Scanner.java` answers that empirically: it
parses `API.txt` the same way `ClassTracer.loadFile()` does (including its
off-by-one -- the file's first line is read but never added to the tracked
list -- so it reports exactly what `ClassTracer` itself would treat as
tracked, not an idealized reading of `API.txt`), then walks every class in
a `.jar`, reporting every `INVOKESPECIAL` instruction whose
owner+name+descriptor matches a tracked entry.

(`INVOKESPECIAL` also covers private-method and `<init>` calls, not just
`super.<method>()` -- but `API.txt` lists only public JDK collection/
concurrency methods on JDK-qualified owners and has no `<init>` entries,
and application bytecode cannot be compiled with a class literally named
e.g. `java/util/ArrayList`. So in practice every match this scanner
reports is a genuine `super.<method>()` call to a tracked JDK type.)

## Files here

```
audit/Scanner.java   -- walks a jar/directory, reports tracked-API INVOKESPECIAL sites
audit/API.txt         -- copy of tsvd4j-core's tracked-API list (the scanner's input)
evidence/
  6-eight-subjects-scan.log   -- scan of all 8 real-world subjects named in tsvd4j-audit-log.html
```

## Reproduce it yourself

Each of the 8 subjects is a published Maven Central artifact, pinned to
the exact version each subject used -- fully reproducible without needing
any particular local Maven cache:

```bash
git clone git@github.com:UT-SE-Research/TSVD4J.git
cd TSVD4J
ASM=$(find ~/.m2/repository/org/ow2/asm -name "asm-9.7.1.jar")   # any ASM 9.x works; mvn dependency:get -Dartifact=org.ow2.asm:asm:9.7.1 if you don't have one

mkdir -p /tmp/scan3/build && cp <this-folder>/audit/Scanner.java <this-folder>/audit/API.txt /tmp/scan3/
cd /tmp/scan3 && javac -cp "$ASM" -d build Scanner.java

for artifact in \
    org.apache.commons:commons-pool2:2.12.0 \
    log4j:log4j:1.2.17 \
    org.apache.derby:derby:10.14.2.0 \
    org.fluentd:fluent-logger:0.3.4 \
    org.javadelight:delight-nashorn-sandbox:0.5.1 \
    org.java-websocket:Java-WebSocket:1.5.4 \
    commons-collections:commons-collections:3.1 \
    commons-dbutils:commons-dbutils:1.7 ; do
  mvn -q dependency:get -Dartifact=$artifact
done

# then run audit.Scanner API.txt <jar> for each resolved jar under ~/.m2/repository
```

## What the evidence shows

| Subject | Project | Classes scanned | Tracked-API INVOKESPECIAL sites |
|---|---|---|---|
| 1 | commons-pool2 2.12.0 | 84 | 0 |
| 2 | log4j 1.2.17 | 314 | 0 |
| 3 | Apache Derby 10.14.2.0 | 1751 | 0 |
| 4 | fluent-logger-java 0.3.4 | 14 | 0 |
| 5 | delight-nashorn-sandbox 0.5.1 | 28 | 0 |
| 6 | Java-WebSocket 1.5.4 | 88 | 0 |
| 7 | commons-collections 3.1 | 446 | **9** (`MultiHashMap extends HashMap`) |
| 8 | commons-dbutils 1.7 | 69 | **5** (`BasicRowProcessor$CaseInsensitiveHashMap extends LinkedHashMap`) |

Two details worth calling out (full hit detail in
`evidence/6-eight-subjects-scan.log`):

- **`FastArrayList`** -- the class subject 7 was originally chosen for --
  is present in that exact jar and was scanned specifically: **0** hits on
  it. It delegates through a private `list` field (composition) rather
  than calling `super.<method>()`, so it isn't itself exposed; the 9 hits
  are all in the unrelated `MultiHashMap` class shipped in the same jar.
- **The commons-dbutils hit is independent of DBUTILS-135** (the
  documented bug that subject 8 was originally chosen to demonstrate,
  which lives in `java.util.ServiceLoader`, outside both this scanner's
  scope and TSVD4J's blacklist-limited reach) -- `BasicRowProcessor`'s own
  `CaseInsensitiveHashMap extends LinkedHashMap` and calls
  `super.get()`/`super.put()`/`super.remove()`/`super.containsKey()`, a
  second exposure in a project this audit had already analyzed, found only
  by scanning the whole dependency rather than the class the bug report
  named.

**Answer:** 2 of the 8 named projects (25%) show the exact exposure, in
both cases in a class unrelated to the specific bug each project was
originally chosen to demonstrate -- not an edge case found by looking for
it, but a side effect of scanning the whole dependency.

Separately, running the same scanner against a broad, non-cherry-picked
sweep of published libraries (spot-checked here against two of the most
widely used: `spring-core:6.2.12` -- 5 hits, `LimitedDataBufferList
extends ArrayList` in WebFlux's buffering code; `jsoup:1.19.1` -- 15 hits,
`Elements`/`ChangeNotifyingArrayList extends ArrayList`) shows the same
exposure recurs across some of the most common dependencies in the wider
Java ecosystem, not just within these 8 subjects.
