package org.endeavourhealth.common.utility;


import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import net.gpedro.integrations.slack.SlackApi;
import net.gpedro.integrations.slack.SlackAttachment;
import net.gpedro.integrations.slack.SlackException;
import net.gpedro.integrations.slack.SlackMessage;
import org.endeavourhealth.common.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class SlackHelper {
    private static final Logger LOG = LoggerFactory.getLogger(SlackHelper.class);

    private static final String PROXY_URL = "proxy_url";
    private static final String PROXY_PORT = "proxy_port";

    //enum to define the channels we can send to
    public enum Channel {
        QueueReaderAlerts("QueueReader"),
        SftpReaderAlerts("SftpReader"),
        EnterprisePersonUpdaterAlerts("EnterprisePersonUpdater"),
        EnterpriseAgeUpdaterAlerts("EnterpriseAgeUpdater"),
        Hl7ReceiverAlertsBarts("Hl7ReceiverBarts"),
        Hl7ReceiverAlertsHomerton("Hl7ReceiverHomerton"),
        JDBCReaderAlertsHomerton("JDBCReaderAlertsHomerton"),
        Hl7Receiver("Hl7Receiver");

        private String channelName = null;

        Channel(String channelName) {
            this.channelName = channelName;
        }

        public String getChannelName() {
            return channelName;
        }
    };

    private static Map<String, String> cachedUrls = null;
    private static Proxy proxy = null;

    /*private static String slackUrl = null;

    public static void setSlackUrl(String slackUrl) {
        SlackHelper.slackUrl = slackUrl;
    }*/

    public static void sendSlackMessage(Channel channel, String message) {
        sendSlackMessage(channel, message, (String)null);
    }

    public static void sendSlackMessage(Channel channel, String message, Exception ex) {

        String attachmentStr = null;
        if (ex != null) {

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            attachmentStr = sw.toString();
        }

        sendSlackMessage(channel, message, attachmentStr);
    }

    public static void sendSlackMessage(Channel channel, String message, String attachment) {

        String slackUrl = findUrl(channel);
        if (Strings.isNullOrEmpty(slackUrl)) {
            LOG.info("No Slack URL set for alerting to channel " + channel);
            return;
        }

        SlackMessage slackMessage = new SlackMessage(message);

        if (!Strings.isNullOrEmpty(attachment)) {
            SlackAttachment slackAttachment = new SlackAttachment();
            slackAttachment.setFallback("Exception cannot be displayed");
            slackAttachment.setText("```" + attachment + "```");
            slackAttachment.addMarkdownAttribute("text"); //this tells Slack to apply the formatting to the text

            slackMessage.addAttachments(slackAttachment);
        }

        try {
            SlackApi slackApi = new SlackApi(slackUrl, proxy);
            slackApi.call(slackMessage);

        } catch (SlackException se) {
            LOG.error("Error sending Slack notification to " + slackUrl, se);
        }
    }

    private static String findUrl(Channel channel) {
        if (cachedUrls == null) {
            readConfig();
        }

        return cachedUrls.get(channel.getChannelName());
    }

    private static synchronized void readConfig() {
        if (cachedUrls != null) {
            return;
        }

        cachedUrls = new HashMap<>();

        try {
            String proxyUrl = null;
            Integer proxyPort = null;

            JsonNode node = ConfigManager.getConfigurationAsJson("slack");
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                String field = it.next();
                JsonNode child = node.get(field);

                if (field.equals(PROXY_URL)) {
                    proxyUrl = child.asText();

                } else if (field.equals(PROXY_PORT)) {
                    proxyPort = new Integer(child.asInt());

                } else {
                    String url = child.asText();
                    cachedUrls.put(field, url);
                }
            }

            //populate the proxy object
            if (!Strings.isNullOrEmpty(proxyUrl)) {
                if (proxyPort == null) {
                    throw new Exception(PROXY_URL + " set in config but " + PROXY_PORT + " missing");
                }

                SocketAddress addr = new InetSocketAddress(proxyUrl, proxyPort.intValue());
                proxy = new Proxy(Proxy.Type.HTTP, addr);
                LOG.debug("Using proxy " + proxyUrl + " port " + proxyPort);

            } else {
                proxy = Proxy.NO_PROXY;
                LOG.debug("Not using proxy");
            }

        } catch (Exception ex) {
            LOG.error("Error reading in slack config", ex);
        }
    }

    public Proxy getProxy() {

        if (cachedUrls == null) {
            readConfig();
        }

        return proxy;
    }
}

