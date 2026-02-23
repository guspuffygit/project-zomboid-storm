package io.pzstorm.storm.jna;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

public abstract class BaseStructure<T> extends Structure {
    public long ptr() {
        this.write();
        Pointer ptr = this.getPointer();
        return Pointer.nativeValue(ptr);
    }

    public abstract T getValue();
}
