package audit;

/**
 * Drives MyList.add() once. Run this uninstrumented -- it just adds "x" and
 * exits. Run it with -javaagent:tsvd4j-core-<version>.jar and it should
 * instead recurse forever between Proxy.add() and MyList.add() until the
 * JVM throws StackOverflowError.
 */
public class Main {
    public static void main(String[] args) {
        MyList list = new MyList();
        list.add("x");
        System.out.println("OK: list = " + list);
    }
}
