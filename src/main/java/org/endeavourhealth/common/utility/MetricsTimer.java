package org.endeavourhealth.common.utility;

import com.codahale.metrics.Timer;

import java.io.Closeable;
import java.io.IOException;

public class MetricsTimer implements Closeable {
    private Timer.Context context;

    public MetricsTimer(Timer.Context context) {
        this.context = context;
    }

    @Override
    public void close() throws IOException {
        this.context.stop();
    }
}
