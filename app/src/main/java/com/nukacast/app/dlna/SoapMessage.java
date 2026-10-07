package com.nukacast.app.dlna;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing and building of UPnP SOAP traffic.
 *
 * <p>Control points (BubbleUPnP, VLC, Windows "Cast to device") speak SOAP over HTTP: the action is in
 * the {@code SOAPACTION} header and the arguments are XML elements in the body. Kept free of Android
 * types so the wire format can be tested directly.
 */
public final class SoapMessage {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Pattern ACTION_PATTERN =
            Pattern.compile("\"([^\"]*)#([^\"]*)\"");
    private static final Pattern ELEMENT_PATTERN =
            // Namespaces matter: dc:title, upnp:class and res are the tags a DIDL blob uses, and a
            // plain word-character pattern silently skipped every one of them.
            Pattern.compile("<([\\w:.-]+)(?:\\s[^>]*)?>([^<]*)</\\1>");

    /** Service type and action name, as sent in the SOAPACTION header. */
    public final String serviceType;
    public final String action;
    public final Map<String, String> arguments;

    private SoapMessage(String serviceType, String action, Map<String, String> arguments) {
        this.serviceType = serviceType;
        this.action = action;
        this.arguments = arguments;
    }

    /** Parses a request; returns null when there is no usable action. */
    public static SoapMessage parse(String soapActionHeader, String body) {
        String serviceType = "";
        String action = "";
        if (soapActionHeader != null) {
            Matcher matcher = ACTION_PATTERN.matcher(soapActionHeader);
            if (matcher.find()) {
                serviceType = matcher.group(1) == null ? "" : matcher.group(1).trim();
                action = matcher.group(2) == null ? "" : matcher.group(2).trim();
            } else {
                // Some control points send the bare action name.
                action = soapActionHeader.replace("\"", "").trim();
                int hash = action.indexOf('#');
                if (hash >= 0) {
                    serviceType = action.substring(0, hash);
                    action = action.substring(hash + 1);
                }
            }
        }
        Map<String, String> arguments = new LinkedHashMap<String, String>();
        if (body != null) {
            Matcher matcher = ELEMENT_PATTERN.matcher(body);
            while (matcher.find()) {
                arguments.put(matcher.group(1), unescape(matcher.group(2)));
            }
        }
        if (action.isEmpty()) return null;
        return new SoapMessage(serviceType, action, arguments);
    }

    public String argument(String name) {
        String value = arguments.get(name);
        return value == null ? "" : value;
    }

    /** UTF-8 bytes of an action response envelope, as UPnP expects. */
    public static byte[] response(String serviceType, String action, Map<String, String> out) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        xml.append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
                .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
                .append("<s:Body>");
        xml.append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">");
        if (out != null) {
            for (Map.Entry<String, String> entry : out.entrySet()) {
                xml.append('<').append(entry.getKey()).append('>')
                        .append(escape(entry.getValue()))
                        .append("</").append(entry.getKey()).append('>');
            }
        }
        xml.append("</u:").append(action).append("Response>");
        xml.append("</s:Body></s:Envelope>");
        return xml.toString().getBytes(UTF_8);
    }

    /** UPnP defines the error code in a fault body; 701 = transition not available, 716 = no such object. */
    public static byte[] fault(String serviceType, String action, int errorCode, String description) {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                + "<errorCode>" + errorCode + "</errorCode>"
                + "<errorDescription>" + escape(description) + "</errorDescription>"
                + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";
        return xml.getBytes(UTF_8);
    }

    /** The MIME type declared inside a DIDL-Lite metadata blob, or an empty string. */
    public static String metadataMimeType(String metadata) {
        if (metadata == null || metadata.isEmpty()) return "";
        Matcher matcher = Pattern.compile("protocolInfo=\"([^\"]*)\"").matcher(metadata);
        if (!matcher.find()) {
            matcher = Pattern.compile("<upnp:protocolInfo>([^<]*)</upnp:protocolInfo>").matcher(metadata);
            if (!matcher.find()) return "";
        }
        // protocolInfo is protocol:network:contentFormat:additionalInfo, so the MIME type is the
        // third field (some control points omit the fourth).
        String[] parts = matcher.group(1).split(":");
        return parts.length >= 3 ? parts[2].trim() : "";
    }

    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    /** Undoes XML entities; also drops CDATA markers, which some control points use for titles. */
    static String unescape(String value) {
        if (value == null || value.isEmpty()) return "";
        String text = value.trim();
        if (text.startsWith("<![CDATA[") && text.endsWith("]]>")) {
            text = text.substring(9, text.length() - 3);
        }
        return text.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#38;", "&").replace("&amp;", "&");
    }

    /** Collects {@code <tag>...</tag>} occurrences, used by the tests and by metadata parsing. */
    public static Map<String, String> elements(String xml) {
        Map<String, String> found = new LinkedHashMap<String, String>();
        if (xml == null) return found;
        Matcher matcher = ELEMENT_PATTERN.matcher(xml);
        while (matcher.find()) found.put(matcher.group(1), unescape(matcher.group(2)));
        return found;
    }

    static byte[] utf8(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = text.getBytes(UTF_8);
        out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }
}
