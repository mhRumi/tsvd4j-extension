package audit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Question 3: how often does TSVD4J's tracked-API list get reached through
 * INVOKESPECIAL in real code -- i.e. how often would the opcode-skip fix
 * (or the pre-fix recursion bug) actually fire?
 *
 * Loads API.txt the same way ClassTracer does (owner/name+desc strings),
 * then walks every method of every class in a directory tree of .class
 * files or inside one or more .jar files, reporting every INVOKESPECIAL
 * instruction whose owner+name+descriptor matches a tracked entry.
 *
 * Note on scope: INVOKESPECIAL covers three cases -- super.<method>(),
 * private-method calls, and <init> constructor calls. API.txt lists only
 * public JDK collection/concurrency API methods on their JDK-qualified
 * owner (e.g. "java/util/ArrayList"), and no <init> entries -- so a
 * private-method or constructor invokespecial in application code can
 * only match if that code's own class were literally named
 * "java/util/ArrayList" etc., which application bytecode cannot do. In
 * practice every match this scanner reports is a genuine super call to a
 * tracked JDK method.
 */
public class Scanner {
    static List<String> listAPI;

    public static void main(String[] args) throws Exception {
        loadApiList(args.length > 0 ? args[0] : null);

        List<Path> targets = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--list")) {
                for (String line : Files.readAllLines(Paths.get(args[++i]))) {
                    if (!line.isBlank()) {
                        targets.add(Paths.get(line.trim()));
                    }
                }
            } else {
                targets.add(Paths.get(args[i]));
            }
        }

        int totalClasses = 0;
        int totalHits = 0;
        for (Path target : targets) {
            if (Files.isDirectory(target)) {
                try (Stream<Path> walk = Files.walk(target)) {
                    List<Path> classFiles = walk.filter(p -> p.toString().endsWith(".class")).toList();
                    for (Path p : classFiles) {
                        totalClasses++;
                        try (InputStream is = Files.newInputStream(p)) {
                            totalHits += scanOne(target.relativize(p).toString(), is.readAllBytes());
                        } catch (Exception e) {
                            System.err.println("SKIP " + p + ": " + e);
                        }
                    }
                }
            } else if (target.toString().endsWith(".jar")) {
                try (JarInputStream jis = new JarInputStream(Files.newInputStream(target))) {
                    JarEntry entry;
                    while ((entry = jis.getNextJarEntry()) != null) {
                        if (entry.getName().endsWith(".class")) {
                            totalClasses++;
                            try {
                                totalHits += scanOne(target.getFileName() + "!" + entry.getName(), jis.readAllBytes());
                            } catch (Exception e) {
                                System.err.println("SKIP " + target.getFileName() + "!" + entry.getName() + ": " + e);
                            }
                        }
                    }
                }
            } else {
                System.err.println("Skipping unrecognized target: " + target);
            }
        }
        System.out.println("---");
        System.out.println("Classes scanned: " + totalClasses);
        System.out.println("Tracked-API INVOKESPECIAL sites found: " + totalHits);
    }

    private static int scanOne(String label, byte[] bytes) throws IOException {
        final int[] hits = {0};
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            String className;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                this.className = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                final String enclosingMethod = className + "." + name + desc;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
                        if (opcode == Opcodes.INVOKESPECIAL) {
                            String combined = owner + "/" + mname + mdesc;
                            if (listAPI.contains(combined)) {
                                hits[0]++;
                                System.out.println("HIT  " + label + "  [" + enclosingMethod + "]  -> " + combined);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return hits[0];
    }

    // Mirrors ClassTracer.loadFile()'s parsing exactly, including its
    // off-by-one (the file's first line is read but never added to the
    // list) -- so this scanner reports precisely what ClassTracer itself
    // would treat as "tracked", not an idealized reading of API.txt.
    private static void loadApiList(String apiTxtPath) throws IOException {
        listAPI = new ArrayList<>();
        try (var reader = Files.newBufferedReader(Paths.get(apiTxtPath))) {
            String line = reader.readLine();
            while (line != null) {
                line = reader.readLine();
                listAPI.add(line);
            }
        }
    }
}
