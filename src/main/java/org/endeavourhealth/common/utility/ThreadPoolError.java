package org.endeavourhealth.common.utility;

import java.util.concurrent.Callable;

public class ThreadPoolError {
    private Callable callable = null;
    private Exception exception = null;

    public ThreadPoolError(Callable callable, Exception exception) {
        this.callable = callable;
        this.exception = exception;
    }

    public Callable getCallable() {
        return callable;
    }

    public Exception getException() {
        return exception;
    }
}