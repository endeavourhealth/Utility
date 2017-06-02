package org.endeavourhealth.common.utility;

import com.google.common.base.Charsets;

import java.io.IOException;
import java.net.URL;

public class Resources {

    public static String getResourceAsString(String url) throws IOException {
        URL urlItem = getResourceAsURLObject(url);
        String text = com.google.common.io.Resources.toString(urlItem, Charsets.UTF_8);
        return text;
    }

    public static byte[] getResourceAsBytes(String url) throws IOException {
        URL urlItem = getResourceAsURLObject(url);
        return com.google.common.io.Resources.toByteArray(urlItem);
    }

    public static URL getResourceAsURLObject(String url) throws IOException {
        URL urlItem = com.google.common.io.Resources.getResource(url);
        return urlItem;
    }
}
