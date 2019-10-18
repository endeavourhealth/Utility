package org.endeavourhealth.common.utility;

import com.google.common.base.Strings;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * ThreadFactory implementation so that we can specify a name prefix for the threads in the pool
 * so logging is clearer
 */
public class NamingThreadFactory implements ThreadFactory {

    private ThreadFactory defaultFactory;
    private String poolName;

    public NamingThreadFactory(String poolName) {
        this.defaultFactory = Executors.defaultThreadFactory();
        this.poolName = poolName;
    }

    @Override
    public Thread newThread(Runnable r) {

        Thread t = defaultFactory.newThread(r);
        if (!Strings.isNullOrEmpty(poolName)) {
            String newName = poolName + "-" + t.getName();
            t.setName(newName);
        }
        return t;
    }
}