package org.endeavourhealth.common.utility;

import java.util.concurrent.Callable;

public class ThreadPoolError {
    private Callable callable = null;
    private Throwable exception = null;

    public ThreadPoolError(Callable callable, Throwable exception) {
        this.callable = callable;
        this.exception = exception;
    }

    public Callable getCallable() {
        return callable;
    }

    public Throwable getException() {
        return exception;
    }
}