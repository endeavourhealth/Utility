package org.endeavourhealth.common.utility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


public class ThreadPool {
    private static final Logger LOG = LoggerFactory.getLogger(ThreadPool.class);

    private final ExecutorService threadPool;
    private final AtomicInteger threadPoolQueueSize = new AtomicInteger();
    private final ReentrantLock futuresLock = new ReentrantLock();
    private final Map<Future, Callable> futures = new ConcurrentHashMap<>();
    private final AtomicInteger futureCheckCounter = new AtomicInteger();
    private final int maxQueuedBeforeBlocking;
    private final ReentrantLock isEmptyLock = new ReentrantLock();
    private final Condition isEmptyCondition = isEmptyLock.newCondition();

    public ThreadPool(int threads, int maxQueuedBeforeBlocking) {
        this.threadPool = Executors.newFixedThreadPool(threads);
        this.maxQueuedBeforeBlocking = maxQueuedBeforeBlocking;
    }

    /**
     * submits a new callable to the thread pool, and occasionally returns a list of errors that
     * have occured with previously submitted callables
     */
    public List<ThreadPoolError> submit(Callable callable) {
        threadPoolQueueSize.incrementAndGet();
        Future future = threadPool.submit(new CallableWrapper(callable));

        //cache the Future object, so we can check for errors later
        try {
            futuresLock.lock();
            futures.put(future, callable);
        } finally {
            futuresLock.unlock();
        }

        //if our queue is now at our limit, then block the current thread before the queue is smaller
        while (threadPoolQueueSize.get() >= maxQueuedBeforeBlocking) {
            try {
                Thread.sleep(250); //would probably be more elegant to await on a condition but this is simpler
            } catch (InterruptedException ex) {
                //if we get interrupted, don't log the error
            }
        }

        //check the futures every so often to see if any are done or any exceptions were raised
        int counter = futureCheckCounter.incrementAndGet();
        if (counter % 250 == 0) { //choice of 250 was pretty arbitrary
            futureCheckCounter.set(0);

            //LOG.trace("Checking {} futures with {} items in pool", futures.size(), threadPoolQueueSize);
            //LOG.trace("Free mem {} ", Runtime.getRuntime().freeMemory());
            return checkFuturesForErrors(false);
        } else {
            return new ArrayList<>();
        }
    }

    /**
     * blocks until the thread pool is empty
     * NOTE: this does not prevent new tasks being added to the thread pool
     */
    public List<ThreadPoolError> waitUntilEmpty() {

        try {
            isEmptyLock.lock();

            int attempts = 0;

            //if our queue is now at our limit, then block the current thread before the queue is smaller
            while (threadPoolQueueSize.get() > 0) {
                try {
                    //dynamically calculate the sleep based on how long we've waited so far, so that we don't
                    //wait too long initially, but then don't waste time with context switching once we've been waiting a while
                    attempts ++;
                    long delay = Math.min(25 * (((long)attempts / 4) + 1), 500);

                    isEmptyCondition.await(delay, TimeUnit.MILLISECONDS);

                } catch (InterruptedException ex) {
                    //if we get interrupted, don't log the error
                }
            }

        } finally {
            isEmptyLock.unlock();
        }

        return checkFuturesForErrors(false);
    }
    /*public List<ThreadPoolError> waitUntilEmpty() {

        int attempts = 0;

        //if our queue is now at our limit, then block the current thread before the queue is smaller
        while (threadPoolQueueSize.get() > 0) {
            try {
                //dynamically calculate the sleep based on how long we've waited so far, so that we don't
                //wait too long initially, but then don't waste time with context switching once we've been waiting a while
                attempts ++;
                long delay = Math.min(25 * (((long)attempts / 4) + 1), 500);

                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                //if we get interrupted, don't log the error
            }
        }

        return checkFuturesForErrors(false);
    }*/

    /**
     * shuts down the thread pool, so no more callables can be added, then waits for them to complete
     * and returns a list of errors that have happened with callables
     */
    public List<ThreadPoolError> waitAndStop() {
        return waitAndStop(1, TimeUnit.MINUTES);
    }
    public List<ThreadPoolError> waitAndStop(long checkInterval, TimeUnit unit) {

        threadPool.shutdown();

        try {
            while (!threadPool.awaitTermination(checkInterval, unit)) {
                LOG.trace("Waiting for {} tasks to complete", threadPoolQueueSize.get());
            }
        } catch (InterruptedException ex) {
            LOG.error("Thread interrupted", ex);
        }

        return checkFuturesForErrors(true);
    }

    private List<ThreadPoolError> checkFuturesForErrors(boolean forceLock) {

        try {

            //when finishing processing, we want to guarantee a lock, but when doing an interim check,
            //we just try to get the lock and back off if we can't
            if (forceLock) {
                futuresLock.lock();
            } else {
                if (!futuresLock.tryLock()) {
                    return new ArrayList<>();
                }
            }

            List<ThreadPoolError> ret = new ArrayList<>();

            //check all the futures to see if any raised an error. Also, remove any futures that
            //we know have completed without error.
            Iterator<Map.Entry<Future, Callable>> it = futures.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Future, Callable> entry = it.next();
                Future future = entry.getKey();

                if (future.isDone()) {
                    //if it's done, remove from the iterator, which will remove from the map
                    it.remove();

                    //calling get() on the future will cause any exception from the task execution to be raised in this thread
                    try {
                        future.get();
                    } catch (Exception ex) {

                        //the true exception will be inside an ExecutionException, so get it out and wrap in our own exception
                        Throwable cause = ex.getCause();
                        Callable callable = entry.getValue();

                        ret.add(new ThreadPoolError(callable, cause));
                    }
                }
            }

            return ret;

        } finally {
            futuresLock.unlock();
        }
    }

    class CallableWrapper implements Callable {
        private Callable callable = null;

        public CallableWrapper(Callable callable) {
            this.callable = callable;
        }

        @Override
        public Object call() throws Exception {

            int sizeAfterCompletion;
            Object ret;
            try {
                ret = callable.call();
            } finally {
                sizeAfterCompletion = threadPoolQueueSize.decrementAndGet();
            }

            //if the pool is now empty, we should attempt to signal any thread waiting on that
            if (sizeAfterCompletion == 0) {
                try {
                    isEmptyLock.lock();
                    isEmptyCondition.signalAll();
                } finally {
                    isEmptyLock.unlock();
                }
            }

            return ret;
        }
    }

}




