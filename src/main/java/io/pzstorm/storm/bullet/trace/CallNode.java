package io.pzstorm.storm.bullet.trace;

import java.util.ArrayList;
import java.util.List;

/** One recorded native call: inputs, outputs and the upcalls it made (in order). */
public final class CallNode {

    public final long seq;
    public final long threadId;
    public final Sig sig;

    /** Argument snapshots at call entry. */
    public final Object[] args;

    public final List<UpcallNode> upcalls = new ArrayList<>();

    /** Snapshots of the arguments after the call; non-null only at mutable parameter indexes. */
    public Object[] argsAfter;

    public Object ret;
    public String thrownClass;
    public String thrownMessage;
    public boolean completed;
    public long endSeq = -1;

    public CallNode(long seq, long threadId, Sig sig, Object[] args) {
        this.seq = seq;
        this.threadId = threadId;
        this.sig = sig;
        this.args = args;
        this.argsAfter = new Object[args.length];
    }

    public boolean threw() {
        return thrownClass != null;
    }

    /** Number of calls in this subtree including this one. */
    public int size() {
        int n = 1;
        for (UpcallNode u : upcalls) {
            for (CallNode c : u.calls) {
                n += c.size();
            }
        }
        return n;
    }

    public String signatureWithArgs() {
        StringBuilder sb = new StringBuilder(sig.name).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(Values.describe(sig.params[i], args[i]));
        }
        return sb.append(')').toString();
    }

    @Override
    public String toString() {
        return "#" + seq + " [t" + threadId + "] " + signatureWithArgs();
    }
}
