package org.endeavourhealth.common.utility;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import org.endeavourhealth.common.utility.ExpiringCache.Duration;
/**
 * simple cache class, using a concurrent map, to allow us cache items and expire them automatically
 * NOTE: this class doesn't remove expired objects, so won't free up memory automatically
 */
public class ExpiringSet<V> implements Set<V> {

    private final Set<V> store = new HashSet<>();
    private final Map<V, ExpiringCacheElement<V>> innerMap = new ConcurrentHashMap<>();
    private final long msDuration ;

    public ExpiringSet(long msDuration) {
        this.msDuration = msDuration;
    }

    public ExpiringSet(Duration d) {

        this.msDuration = d.getMs();
    }

    public ExpiringSet(long msDuration, int initialCapacity) {
        this.msDuration = msDuration;
    }


    public ExpiringSet(long msDuration, Collection<? extends V> c) {
        this.msDuration = msDuration;
    }

    @Override
    public boolean add(V value) {
        ExpiringCacheElement<V> existing = innerMap.put(value, new ExpiringCacheElement<V>(value, msDuration));
        if (existing == null
                || existing.isExpired()) {
            return false;
        } else {
            return true;
        }
    }

    @Override
    public boolean remove(Object key) {
        ExpiringCacheElement<V> existing = innerMap.remove(key);
        if (existing == null
                || existing.isExpired()) {
            return false;
        } else {
            return true;
        }
    }
    @Override
    public boolean contains(Object key) {
        ExpiringCacheElement<V> element = innerMap.get(key);
        if (element == null
                || element.isExpired()) {
            return false;
        } else {
            return true;
        }

    }
    @Override
    public int size() {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

    @Override
    public boolean isEmpty() {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

    @Override
    public Iterator<V> iterator() {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

    @Override
    public Object[] toArray() {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

    @Override
    public <T> T[] toArray(T[] a) {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

    @Override
    public boolean addAll(Collection<? extends V> c) {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

    //@Override
    public boolean addAll(int index, Collection<? extends V> c) {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

    @Override
    public void clear() {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new RuntimeException("Function not supported in ExpiringSet");
    }

}
