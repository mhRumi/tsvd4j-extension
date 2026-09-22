package audit;

import java.util.ArrayList;

/**
 * Same shape as MyList, renamed for the second experiment: does skipping
 * INVOKESPECIAL cost TSVD4J a real detection? add() is a plain override
 * that forwards to super.add(o) -- no synchronization anywhere, exactly
 * like MyList. The only difference from MyList is intent: this class is
 * driven by RaceTest below with genuinely concurrent, unsynchronized
 * callers, so the super.add(o) call site is a real racing access, not
 * just a recursion trigger.
 */
public class RaceList extends ArrayList {
    @Override
    public boolean add(Object o) {
        return super.add(o);
    }
}
