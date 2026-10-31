package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayDeque;

/** Single-threaded ConcurrentLinkedQueue for the browser build: the calls nav makes. */
public class TConcurrentLinkedQueue<E> {
    private final ArrayDeque<E> queue = new ArrayDeque<>();

    public boolean offer(E e) {
        return queue.offer(e);
    }

    public boolean add(E e) {
        return queue.add(e);
    }

    public E poll() {
        return queue.poll();
    }

    public E peek() {
        return queue.peek();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public void clear() {
        queue.clear();
    }
}
