package co.multiply.tropical.runtime;

import clojure.lang.Atom;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.PersistentArrayMap;
import java.util.List;

/**
 * Mutable bookkeeping for one mounted island, owned by the session pump.
 * No instance is put into a frame: frame and template values remain immutable
 * snapshots. A render commits its holds and reads before visiting children,
 * so disposal always sees the current ownership after a child aborts.
 */
public final class Instance {
    public final String id;
    public final String parent;
    public final Atom state = new Atom(PersistentArrayMap.EMPTY);
    public Object render;
    public IPersistentVector args;
    public IPersistentMap holds = PersistentArrayMap.EMPTY;
    public ReadBuffer reads = new ReadBuffer();
    public ReadBuffer nextReads = new ReadBuffer();
    public IPersistentMap template;
    public IPersistentMap frame;
    public List<?> calls;
    public IPersistentVector childIds;
    public boolean kept;
    public boolean pending;
    public long renders;
    /** The run of failures the island is in, or null after a successful render. */
    public Failure failure;

    public Instance(String id, String parent) {
        this.id = id;
        this.parent = parent;
    }

    /** Called only after dropping subscriptions absent from the new reads. */
    public void commitReads() {
        ReadBuffer previous = reads;
        reads = nextReads;
        nextReads = previous;
        previous.clear();
    }
}
