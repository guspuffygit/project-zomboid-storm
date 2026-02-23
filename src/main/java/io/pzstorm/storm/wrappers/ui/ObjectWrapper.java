package io.pzstorm.storm.wrappers.ui;

public abstract class ObjectWrapper {

    protected final Object object;
    protected final Class<?> clazz;

    public ObjectWrapper(Object object) {
        this.object = object;
        this.clazz = object.getClass();
    }
}
