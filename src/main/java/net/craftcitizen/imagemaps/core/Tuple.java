package net.craftcitizen.imagemaps.core;

/**
 * Immutable pair of two values.
 */
public final class Tuple<K, V> {
    private final K key;
    private final V value;

    public Tuple(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public K getKey() {
        return key;
    }

    public V getValue() {
        return value;
    }
}
