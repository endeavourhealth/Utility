package org.endeavourhealth.common.utility;

/**
 * simple object wrapper that flags the data as expired after a period of time, so avoid repeated code
 * NOTE: this class doesn't remove expired objects, so won't free up memory automatically
 */
public class ExpiringObject<T> {

    private final long msDuration;
    private T cached;
    private long cachedExpiry;

    public ExpiringObject(long msDuration) {
        this.msDuration = msDuration;
    }

    public static ExpiringObject factoryOneMinute() {
        return new ExpiringObject(1000L * 60L);
    }

    public static ExpiringObject factoryFiveMinutes() {
        return new ExpiringObject(1000L * 60L * 5L);
    }

    public T get() {
        if (cached == null) {
            return null;

        } else if (isExpired()) {
            return null;

        } else {
            return this.cached;
        }
    }

    public void set(T obj) {
        this.cachedExpiry = java.lang.System.currentTimeMillis() + msDuration;
        this.cached = obj;
    }

    private boolean isExpired() {
        return java.lang.System.currentTimeMillis() > cachedExpiry;
    }
}
