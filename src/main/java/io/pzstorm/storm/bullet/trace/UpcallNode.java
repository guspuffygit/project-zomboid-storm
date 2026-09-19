package io.pzstorm.storm.bullet.trace;

import java.util.ArrayList;
import java.util.List;

/** One recorded upcall made by a native call, with the native calls Java issued inside it. */
public final class UpcallNode {

    public final long seq;
    public final long threadId;
    public final Sig sig;
    public final Object[] args;
    public final List<CallNode> calls = new ArrayList<>();

    public Object ret;
    public String thrownClass;
    public String thrownMessage;
    public boolean completed;

    public UpcallNode(long seq, long threadId, Sig sig, Object[] args) {
        this.seq = seq;
        this.threadId = threadId;
        this.sig = sig;
        this.args = args;
    }

    public boolean threw() {
        return thrownClass != null;
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
        return "#" + seq + " upcall " + signatureWithArgs();
    }
}
