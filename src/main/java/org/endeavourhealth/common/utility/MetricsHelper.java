package org.endeavourhealth.common.utility;

import com.codahale.metrics.*;
import com.codahale.metrics.graphite.Graphite;
import com.codahale.metrics.graphite.GraphiteReporter;
import com.fasterxml.jackson.databind.JsonNode;
import org.endeavourhealth.common.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class MetricsHelper {
    private static final Logger LOG = LoggerFactory.getLogger(MetricsHelper.class);

    private static MetricsHelper instance;
    private static Object syncObj = new Object();

    private MetricRegistry registry;

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

        this.registry = new MetricRegistry();

        try {
            JsonNode json = ConfigManager.getConfigurationAsJson("metrics");
            if (json != null) {

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
                }

                JsonNode graphiteNode = json.get("graphite");
                if (graphiteNode != null) {
                    String address = graphiteNode.get("address").asText();
                    int port = graphiteNode.get("port").asInt();

                    //all events should be prefixed with the server name and app
                    //e.g. N3-MessagingAPI-01.messaging-api
                    InetAddress ip = InetAddress.getLocalHost();
                    String hostname = ip.getHostName();
                    String appId = ConfigManager.getAppId();
                    String prefix = hostname + "." + appId;

                    Graphite graphite = new Graphite(new InetSocketAddress(address, port));

                    //the below variables came from https://metrics.dropwizard.io/4.0.0/manual/graphite.html#manual-graphite
                    GraphiteReporter reporter = GraphiteReporter.forRegistry(registry)
                            .prefixedWith(prefix)
                            .convertRatesTo(TimeUnit.SECONDS)
                            .convertDurationsTo(TimeUnit.MILLISECONDS)
                            .filter(MetricFilter.ALL)
                            .build(graphite);
                    reporter.start(1, TimeUnit.MINUTES);
                }
            } else {
                LOG.info("No metrics config record found");
            }

        } catch (Exception ex) {
            LOG.error("Error loading graphite config record", ex);
        }
    }

    private void recordValueImpl(String metric, long value) {
        Histogram histogram = registry.histogram(metric);
        histogram.update(value);
    }

    private void recordEventImpl(String metric) {
        Meter requests = registry.meter(metric);
        requests.mark();
    }

    public static void recordValue(String metric, long value) {
        instance().recordValueImpl(metric, value);
    }

    public static void recordEvent(String metric) {
        instance().recordEventImpl(metric);
    }
}
