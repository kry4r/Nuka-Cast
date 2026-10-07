package com.nukacast.app.dlna;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The DLNA wire format: what control points send, and what they must get back.
 */
public class DlnaSoapTest {
    @Test
    public void parsesTheSoapActionHeader() {
        SoapMessage message = SoapMessage.parse(
                "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", "");
        assertNotNull(message);
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", message.serviceType);
        assertEquals("SetAVTransportURI", message.action);
    }

    @Test
    public void parsesArgumentsAndUnescapesThem() {
        String body = "<?xml version=\"1.0\"?><s:Envelope "
                + "xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + "<InstanceID>0</InstanceID>"
                + "<CurrentURI>http://192.168.5.9:8080/a.m3u8?x=1&amp;y=2</CurrentURI>"
                + "<CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;老友记&lt;/dc:title&gt;"
                + "&lt;res protocolInfo=\"http-get:*:video/mp4:*\"&gt;http://x/y.mp4&lt;/res&gt;"
                + "&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>"
                + "</u:SetAVTransportURI></s:Body></s:Envelope>";
        SoapMessage message = SoapMessage.parse(
                "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", body);
        assertNotNull(message);
        assertEquals("0", message.argument("InstanceID"));
        assertEquals("http://192.168.5.9:8080/a.m3u8?x=1&y=2", message.argument("CurrentURI"));
        assertEquals("老友记", SoapMessage.elements(message.argument("CurrentURIMetaData")).get("dc:title"));
        assertEquals("video/mp4", SoapMessage.metadataMimeType(message.argument("CurrentURIMetaData")));
    }

    @Test
    public void rejectsRequestsWithoutAnAction() {
        assertNull(SoapMessage.parse(null, "<x/>"));
        assertNull(SoapMessage.parse("", ""));
    }

    @Test
    public void buildsAResponseEnvelope() {
        Map<String, String> out = SoapMessage.elements("<a>1</a>");
        String xml = new String(SoapMessage.response("urn:schemas-upnp-org:service:AVTransport:1",
                "GetTransportInfo", new java.util.LinkedHashMap<String, String>() {{
                    put("CurrentTransportState", "PLAYING");
                    put("CurrentTransportStatus", "OK");
                }}), java.nio.charset.Charset.forName("UTF-8"));
        assertTrue(xml.contains("<u:GetTransportInfoResponse"));
        assertTrue(xml.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"));
        assertTrue(xml.contains("</s:Envelope>"));
        // UTF-8, not the platform default: a Chinese title must survive.
        byte[] bytes = SoapMessage.response("urn:x", "Y",
                new java.util.LinkedHashMap<String, String>() {{ put("A", "老友记"); }});
        assertTrue(new String(bytes, java.nio.charset.Charset.forName("UTF-8")).contains("老友记"));
    }

    @Test
    public void buildsAnUpnpFault() {
        String xml = new String(SoapMessage.fault("urn:x", "SetAVTransportURI", 714, "Illegal MIME-type"),
                java.nio.charset.Charset.forName("UTF-8"));
        assertTrue(xml.contains("<errorCode>714</errorCode>"));
        assertTrue(xml.contains("<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"));
    }

    @Test
    public void descriptionAdvertisesTheThreeServices() {
        DlnaDescription.Device device = new DlnaDescription.Device();
        device.uuid = "abc-123";
        device.friendlyName = "NukaCast 电视";
        device.baseUrl = "http://192.168.5.3:9978";
        String xml = DlnaDescription.device(device);
        assertTrue(xml.contains("<friendlyName>NukaCast 电视</friendlyName>"));
        assertTrue(xml.contains("<UDN>uuid:abc-123</UDN>"));
        assertTrue(xml.contains("http://192.168.5.3:9978/dlna/control/AVTransport"));
        assertTrue(xml.contains(DlnaDescription.SERVICE_AV_TRANSPORT));
        assertTrue(xml.contains(DlnaDescription.SERVICE_RENDERING_CONTROL));
        assertTrue(xml.contains(DlnaDescription.SERVICE_CONNECTION_MANAGER));
        assertTrue(xml.contains("urn:schemas-upnp-org:device:MediaRenderer:1"));
    }

    @Test
    public void scpdListsTheActionsAControlPointCalls() {
        String avTransport = DlnaDescription.serviceScpd(DlnaDescription.SERVICE_AV_TRANSPORT);
        assertTrue(avTransport.contains("<name>SetAVTransportURI</name>"));
        assertTrue(avTransport.contains("<name>GetPositionInfo</name>"));
        assertTrue(avTransport.contains("<name>Seek</name>"));
        assertTrue(DlnaDescription.serviceScpd(DlnaDescription.SERVICE_RENDERING_CONTROL)
                .contains("<name>SetVolume</name>"));
        assertTrue(DlnaDescription.serviceScpd(DlnaDescription.SERVICE_CONNECTION_MANAGER)
                .contains("<name>GetProtocolInfo</name>"));
    }

    @Test
    public void sinkProtocolInfoCoversTheFormatsTheAppPlays() {
        String sink = DlnaDescription.sinkProtocolInfo();
        assertTrue(sink.contains("application/vnd.apple.mpegurl"));
        assertTrue(sink.contains("video/mp4"));
        assertTrue(sink.contains("video/x-matroska"));
    }
}
