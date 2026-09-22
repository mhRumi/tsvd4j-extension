package edu.utexas.ece.tsvd4j.agent;

// Q4 dispatch-preserving fix: the side-effect half of what Proxy.<method>()
// does, with the delegating call removed. ClassTracer's INVOKESPECIAL branch
// calls this in place of rewriting the call into Proxy.<method>() -- it
// records exactly the same interception point Proxy.add() would (same
// Helper/Utility calls, same Operation.WRITE), but the original INVOKESPECIAL
// instruction is left completely untouched right after this call returns, so
// dispatch semantics are preserved.
//
// Hardcodes Operation.WRITE: a real version needs the same per-method
// READ/WRITE lookup Proxy.java already does by method name, since not every
// tracked API call is a write.
public class TrackOnly {
    public static void track(Object receiver, int lineNumber, String methodName, String className) {
        InterceptionPoint interception = Helper.createInstance(receiver, className, methodName, lineNumber, Operation.WRITE);
        Utility.onCall(interception);
    }
}
