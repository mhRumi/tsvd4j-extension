package audit;

import java.util.ArrayList;

/**
 * Minimal reproducer for the INVOKESPECIAL / super.add() recursion bug.
 *
 * javac compiles the super.add(o) call below as INVOKESPECIAL
 * java/util/ArrayList.add(Ljava/lang/Object;)Z -- the JVM opcode that means
 * "call this exact method on this exact class, bypassing virtual dispatch."
 * That is precisely why super.<method>() exists: MyList.add() overrides
 * add(), so an ordinary virtual call from inside add() back to "add" would
 * just call itself again forever even without any instrumentation.
 *
 * TSVD4J's ClassTracer.visitMethodInsn() does not look at the opcode -- it
 * matches purely on owner+name+descriptor against API.txt, so it rewrites
 * this INVOKESPECIAL exactly the same way it would rewrite a normal
 * INVOKEVIRTUAL call to list.add(o): into a static call to
 * edu.utexas.ece.tsvd4j.agent.Proxy.add(this, o, ...). Proxy.add() then
 * invokes .add() on its receiver with ordinary virtual dispatch. The
 * receiver's runtime type is MyList, so that virtual call lands right back
 * on MyList.add() -- which calls super.add() again, which gets rewritten
 * again, forever.
 */
public class MyList extends ArrayList {
    @Override
    public boolean add(Object o) {
        return super.add(o);
    }
}
