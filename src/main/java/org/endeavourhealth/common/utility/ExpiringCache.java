package org.endeavourhealth.common.utility;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * simple cache class, using a concurrent map, to allow us cache items and expire them automatically
 * NOTE: this class doesn't remove expired objects, so won't free up memory automatically
 */
public class ExpiringCache<K,V> implements Map<K,V> {

    private final Map<K, ExpiringCacheElement<V>> innerMap = new ConcurrentHashMap<>();
    private final long msDuration;

    public ExpiringCache(long msDuration) {
        this.msDuration = msDuration;
    }

    public static ExpiringCache factoryOneMinute() {
        return new ExpiringCache(1000L * 60L);
    }

    public static ExpiringCache factoryFiveMinutes() {
        return new ExpiringCache(1000L * 60L * 5L);
    }

    @Override
    public int size() {
        throw new RuntimeException("Function not supported in ExpiringCache");
    }

    @Override
    public boolean isEmpty() {
        throw new RuntimeException("Function not supported in ExpiringCache");
    }

    /**
     * note that there's no safe way to check if the map contains a key because if you then
     * try to get using that key, it may have expired and now be null, so there's no point keeping this fn
     */
    @Override
    public boolean containsKey(Object key) {
       throw new RuntimeException("Function not supported in ExpiringCache");
    }

    @Override
    public boolean containsValue(Object value) {
        throw new RuntimeException("Function not supported in ExpiringCache");
    }

    @Override
    public V get(Object key) {
        ExpiringCacheElement<V> element = innerMap.get(key);
        if (element == null
                || element.isExpired()) {
            return null;
        } else {
            return element.getObject();
        }
    }

    @Override
    public V put(K key, V value) {
        ExpiringCacheElement<V> existing = innerMap.put(key, new ExpiringCacheElement<V>(value, msDuration));
        if (existing == null
                || existing.isExpired()) {
            return null;
        } else {
            return existing.getObject();
        }
    }

    @Override
    public V remove(Object key) {
        ExpiringCacheElement<V> existing = innerMap.remove(key);
        if (existing == null
                || existing.isExpired()) {
            return null;
        } else {
            return existing.getObject();
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        throw new RuntimeException("Function not supported in ExpiringCache");
    }

    @Override
    public void clear() {
        innerMap.clear();
    }

    @Override
    public Set<K> keySet() {
        throw new RuntimeException("Function not supported in ExpiringCache");
    }

    @Override
    public Collection<V> values() {
        throw new RuntimeException("Function not supported in ExpiringCache");
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        throw new RuntimeException("Function not supported in ExpiringCache");
    }

}

class ExpiringCacheElement<T> {
    private T object;
    private long expiry;

    public ExpiringCacheElement(T object, long msLife) {
        this.object = object;
        this.expiry = java.lang.System.currentTimeMillis() + msLife;
    }

    public boolean isExpired() {
        return java.lang.System.currentTimeMillis() > expiry;
    }

    public T getObject() {
        return object;
    }

    public long getExpiry() {
        return expiry;
    }
}
