package org.teavm.classlib.java.util.concurrent.atomic;

import java.util.Arrays;

/** Single-threaded AtomicReferenceArray for the browser build. */
public class TAtomicReferenceArray<E> {
    private final Object[] array;

    public TAtomicReferenceArray(int length) {
        array = new Object[length];
    }

    public TAtomicReferenceArray(E[] source) {
        array = Arrays.copyOf(source, source.length, Object[].class);
    }

    public final int length() {
        return array.length;
    }

    @SuppressWarnings("unchecked")
    public final E get(int i) {
        return (E) array[i];
    }

    public final void set(int i, E value) {
        array[i] = value;
    }

    public final void lazySet(int i, E value) {
        array[i] = value;
    }

    @SuppressWarnings("unchecked")
    public final E getAndSet(int i, E value) {
        E old = (E) array[i];
        array[i] = value;
        return old;
    }

    public final boolean compareAndSet(int i, E expect, E update) {
        if (array[i] != expect) {
            return false;
        }
        array[i] = update;
        return true;
    }
}
