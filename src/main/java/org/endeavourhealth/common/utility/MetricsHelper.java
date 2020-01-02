package org.endeavourhealth.common.utility;

import com.codahale.metrics.*;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.codahale.metrics.jvm.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import org.endeavourhealth.common.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * see https://metrics.dropwizard.io/4.1.0/ for documentation
 */
public class MetricsHelper {
    private static final Logger LOG = LoggerFactory.getLogger(MetricsHelper.class);

    //report metrics to graphite every minute
    private static final int GRAPHITE_REPORT_FREQUENCY = 1;
    private static final TimeUnit GRAPHITE_REPORT_UNITS = TimeUnit.MINUTES;

    private static MetricsHelper instance;
    private static Object syncObj = new Object();

    private MetricRegistry registry;

    private Map<String, AtomicInteger> eventMap = new ConcurrentHashMap<>();

    private static MetricsHelper instance() {
        if (instance == null) {
            synchronized (syncObj) {
                if (instance == null) {
                    instance = new MetricsHelper();
                }
            }
        }
        return instance;
    }


    private MetricsHelper() {

        //we need config manager to know our app ID before we can properly start
        if (Strings.isNullOrEmpty(ConfigManager.getAppId())) {
            throw new RuntimeException("Trying to start MetricsHelper before ConfigManager is initialised");
        }

        this.registry = new MetricRegistry();

        try {
            JsonNode json = ConfigManager.getConfigurationAsJson("metrics");
            if (json != null) {

                //set any console logging config
                JsonNode consoleNode = json.get("console");
                if (consoleNode != null) {

                    int frequency = 60;
                    JsonNode frequencyNode = consoleNode.get("frequency");
                    if (frequencyNode != null) {
                        frequency = frequencyNode.asInt();
                    }

                    ConsoleReporter reporter = ConsoleReporter.forRegistry(registry)
                            .convertRatesTo(TimeUnit.SECONDS)
                            .convertDurationsTo(TimeUnit.MILLISECONDS)
                            .build();
                    reporter.start(frequency, TimeUnit.SECONDS);
                    LOG.info("Console metrics reporter started");
                }

                //set any graphite logging config
                JsonNode graphiteNode = json.get("graphite");
                if (graphiteNode != null) {
                    String address = graphiteNode.get("address").asText();
                    int port = graphiteNode.get("port").asInt();

                    //all events should be prefixed with the server name and app
                    String hostname = getHostName(); //e.g. N3-MessagingAPI-01.messaging-api
                    String appId = ConfigManager.getAppId(); //e.g. "QueueReader"
                    String subAppId = ConfigManager.getAppSubId(); //e.g. "InboundA"

                    String prefix = hostname + "." + appId;
                    if (!Strings.isNullOrEmpty(subAppId)) {
                        prefix += "." + subAppId;
                    }

                    Graphite graphite = new Graphite(new InetSocketAddress(address, port));

                    //the below variables came from https://metrics.dropwizard.io/4.0.0/manual/graphite.html#manual-graphite
                    GraphiteReporter reporter = GraphiteReporter.forRegistry(registry)
                            .prefixedWith(prefix)
                            .convertRatesTo(TimeUnit.SECONDS)
                            .convertDurationsTo(TimeUnit.MILLISECONDS)
                            .filter(MetricFilter.ALL)
                            .build(graphite);

                    //set up default metrics for whatever app we're running
                    registry.register("Garbage Collection", new GarbageCollectorMetricSet());
                    registry.register("Buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()));
                    registry.register("Memory", new MemoryUsageGaugeSet());
                    registry.register("Threads", new ThreadStatesGaugeSet());
                    registry.register("File Descriptor", new FileDescriptorRatioGauge());

                    //send metrics every minute
                    reporter.start(GRAPHITE_REPORT_FREQUENCY, GRAPHITE_REPORT_UNITS);

                    LOG.info("Graphite metrics reporter started [" + prefix + "]");
                }

            } else {
                LOG.info("No metrics config record found");
            }

        } catch (Exception ex) {
            LOG.error("Error loading graphite config record", ex);
        }
    }

    public static MetricRegistry getRegistry() {
        return instance().registry;
    }


    public static void startHeartbeat() {
        instance().startHeartbeatImpl();
    }

    private void startHeartbeatImpl() {
        //remove any existing one, just in case this is called twice
        registry.remove("heartbeat");

        HeartbeatGaugeImpl gauge = new HeartbeatGaugeImpl();
        registry.register("heartbeat", gauge);
    }

    public static String getHostName() throws IOException {
        Runtime r = Runtime.getRuntime();
        Process p = r.exec("hostname");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            return br.readLine();
        }
    }

    private void recordValueImpl(String metric, long value) {
        Histogram histogram = registry.histogram(metric);
        histogram.update(value);
    }

    private void recordEventImpl(String metric, int num) {
        AtomicInteger val = eventMap.get(metric);
        if (val == null) {
            //if null, sync and make a second check so we're sure we're not registering the gauge twice
            synchronized (eventMap) {
                val = eventMap.get(metric);
                if (val == null) {
                    val = new AtomicInteger(0);
                    eventMap.put(metric, val);

                    EventGaugeImpl gauge = new EventGaugeImpl(metric);
                    registry.register(metric, gauge);
                }
            }
        }

        //finally increment the value
        val.addAndGet(num);
    }

    private MetricsTimer recordTimeImpl(String metric) {
        Timer timer = registry.timer(metric);
        return new MetricsTimer(timer.time());
    }

    private Counter recordCounterImpl(String metric) {
        return registry.counter(metric);
    }

    public static void recordValue(String metric, long value) {
        instance().recordValueImpl(metric, value);
    }

    public static void recordEvent(String metric) {
        recordEvents(metric, 1);
    }

    public static void recordEvents(String metric, int num) {
        instance().recordEventImpl(metric, num);
    }

    public static MetricsTimer recordTime(String metric) {
        return instance().recordTimeImpl(metric);
    }

    public static Counter recordCounter(String metric) {
        return instance().recordCounterImpl(metric);
    }

    /**
     * Gauge for logging discrete events that happen over time. As the recordEvent(..)
     * function is called, an AtomicInteger is incremented. When this gauge is polled for its
     * value, the current value is returned and the int set back to zero.
     */
    class EventGaugeImpl extends CachedGauge<Integer> {

        private final String name;

        public EventGaugeImpl(String name) {
            //cache the value for the same period of time as the reporting to graphite
            super(GRAPHITE_REPORT_FREQUENCY, GRAPHITE_REPORT_UNITS);
            this.name = name;
        }

        @Override
        protected Integer loadValue() {
            AtomicInteger val = eventMap.get(name);
            if (val == null) {
                return new Integer(0);
            } else {
                int intVal = val.getAndSet(0);
                //LOG.debug("Got " + name + " as " + intVal + " and set to zero");
                return new Integer(intVal);
            }
        }
    }

    /*class GaugeImpl implements Gauge<Integer> {

        private final String name;

        public GaugeImpl(String name) {
            this.name = name;
        }

        @Override
        public Integer getValue() {
            AtomicInteger val = eventMap.get(name);
            if (val == null) {
                return new Integer(0);
            } else {
                int intVal = val.getAndSet(0);
                LOG.debug("Got " + name + " as " + intVal + " and set to zero");
                return new Integer(intVal);
            }
        }
    }*/

    /**
     * simple gauge that just reports a value of 1 whenever polled, to report the application is running
     */
    class HeartbeatGaugeImpl implements Gauge<Integer> {

        public HeartbeatGaugeImpl() {}

        @Override
        public Integer getValue() {
            return new Integer(1);
        }
    }
}
