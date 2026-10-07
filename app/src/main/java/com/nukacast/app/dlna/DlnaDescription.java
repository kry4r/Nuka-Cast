package com.nukacast.app.dlna;

import java.util.Locale;

/**
 * The XML documents a DLNA control point downloads.
 *
 * <p>Device description, the three service descriptions and the SSDP search response. Everything is
 * built from one {@link Device} value, because UPnP requires the friendly name, the UDN and every URL
 * to agree between the description, the SSDP replies and the event messages.
 */
public final class DlnaDescription {
    public static final String DEVICE_TYPE_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";
    public static final String SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1";
    public static final String SERVICE_CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1";

    /** Everything the documents need to agree on. */
    public static final class Device {
        public String friendlyName = "NukaCast";
        public String uuid = "";
        public String modelName = "NukaCast";
        public String modelNumber = "";
        public String baseUrl = "";
        public String serial = "";

        public String udn() {
            return "uuid:" + uuid;
        }
    }

    private DlnaDescription() {}

    /** The device description served at {@code /dlna/description.xml}. */
    public static String device(Device device) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\"?>\n");
        xml.append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\n");
        xml.append("<specVersion><major>1</major><minor>0</minor></specVersion>\n");
        xml.append("<device>\n");
        xml.append("<deviceType>").append(DEVICE_TYPE_RENDERER).append("</deviceType>\n");
        xml.append("<friendlyName>").append(SoapMessage.escape(device.friendlyName)).append("</friendlyName>\n");
        xml.append("<manufacturer>NukaCast</manufacturer>\n");
        xml.append("<manufacturerURL>https://github.com/kry4r/Nuka-Cast</manufacturerURL>\n");
        xml.append("<modelDescription>NukaCast 投屏接收</modelDescription>\n");
        xml.append("<modelName>").append(SoapMessage.escape(device.modelName)).append("</modelName>\n");
        xml.append("<modelNumber>").append(SoapMessage.escape(device.modelNumber)).append("</modelNumber>\n");
        xml.append("<serialNumber>").append(SoapMessage.escape(device.serial)).append("</serialNumber>\n");
        xml.append("<UDN>").append(device.udn()).append("</UDN>\n");
        xml.append("<serviceList>\n");
        xml.append(serviceEntry(SERVICE_AV_TRANSPORT, "AVTransport",
                device.baseUrl + "/dlna/service/AVTransport.xml",
                device.baseUrl + "/dlna/control/AVTransport",
                device.baseUrl + "/dlna/event/AVTransport"));
        xml.append(serviceEntry(SERVICE_RENDERING_CONTROL, "RenderingControl",
                device.baseUrl + "/dlna/service/RenderingControl.xml",
                device.baseUrl + "/dlna/control/RenderingControl",
                device.baseUrl + "/dlna/event/RenderingControl"));
        xml.append(serviceEntry(SERVICE_CONNECTION_MANAGER, "ConnectionManager",
                device.baseUrl + "/dlna/service/ConnectionManager.xml",
                device.baseUrl + "/dlna/control/ConnectionManager",
                device.baseUrl + "/dlna/event/ConnectionManager"));
        xml.append("</serviceList>\n");
        xml.append("</device>\n</root>\n");
        return xml.toString();
    }

    private static String serviceEntry(String serviceType, String serviceId, String scpd,
                                       String control, String event) {
        return "<service><serviceType>" + serviceType + "</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:" + serviceId + "</serviceId>"
                + "<SCPDURL>" + scpd + "</SCPDURL>"
                + "<controlURL>" + control + "</controlURL>"
                + "<eventSubURL>" + event + "</eventSubURL></service>";
    }

    /**
     * Service description (SCPD).
     *
     * <p>Control points read the action list; the state-variable table is what makes them poll for
     * position and volume instead of failing, so both are emitted.
     */
    public static String serviceScpd(String serviceType) {
        if (SERVICE_AV_TRANSPORT.equals(serviceType)) {
            return scpd(serviceType,
                    new String[]{
                            "SetAVTransportURI", "SetNextAVTransportURI", "GetMediaInfo",
                            "GetTransportInfo", "GetPositionInfo", "GetDeviceCapabilities",
                            "GetTransportSettings", "Stop", "Play", "Pause", "Seek", "Next",
                            "Previous", "GetCurrentTransportActions"},
                    new String[]{
                            variable("TransportState", "string"),
                            variable("TransportStatus", "string"),
                            variable("CurrentTrackDuration", "string"),
                            variable("CurrentTrackMetaData", "string"),
                            variable("CurrentTrackURI", "string"),
                            variable("CurrentTransportActions", "string"),
                            variable("AVTransportURI", "string"),
                            variable("AVTransportURIMetaData", "string"),
                            variable("NumberOfTracks", "ui4"),
                            variable("CurrentTrack", "ui4"),
                            variable("PlaybackStorageMedium", "string"),
                            variable("PossiblePlaybackStorageMedia", "string")});
        }
        if (SERVICE_RENDERING_CONTROL.equals(serviceType)) {
            return scpd(serviceType,
                    new String[]{"GetVolume", "SetVolume", "GetMute", "SetMute", "ListPresets",
                            "SelectPreset", "GetVolumeDBRange"},
                    new String[]{
                            variable("Volume", "ui2"),
                            variable("Mute", "boolean"),
                            variable("PresetNameList", "string")});
        }
        return scpd(SERVICE_CONNECTION_MANAGER,
                new String[]{"GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo"},
                new String[]{variable("SourceProtocolInfo", "string"),
                        variable("SinkProtocolInfo", "string"),
                        variable("CurrentConnectionIDs", "string")});
    }

    private static String variable(String name, String type) {
        return "<stateVariable sendEvents=\"no\"><name>" + name + "</name><dataType>" + type
                + "</dataType></stateVariable>";
    }

    private static String scpd(String serviceType, String[] actions, String[] variables) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\"?>\n");
        xml.append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n");
        xml.append("<specVersion><major>1</major><minor>0</minor></specVersion>\n<actionList>\n");
        for (String action : actions) {
            xml.append("<action><name>").append(action).append("</name>");
            xml.append("<argumentList>");
            for (String[] argument : argumentsOf(serviceType, action)) {
                xml.append("<argument><name>").append(argument[0]).append("</name>")
                        .append("<direction>").append(argument[1]).append("</direction>")
                        .append("<relatedStateVariable>").append(argument[2])
                        .append("</relatedStateVariable></argument>");
            }
            xml.append("</argumentList></action>\n");
        }
        xml.append("</actionList>\n<serviceStateTable>\n");
        for (String variable : variables) xml.append(variable).append('\n');
        xml.append("</serviceStateTable>\n</scpd>\n");
        return xml.toString();
    }

    /** Argument lists, which several control points insist on before they call an action. */
    private static String[][] argumentsOf(String serviceType, String action) {
        if (SERVICE_AV_TRANSPORT.equals(serviceType)) {
            if ("SetAVTransportURI".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"CurrentURI", "in", "AVTransportURI"},
                        {"CurrentURIMetaData", "in", "AVTransportURIMetaData"}};
            }
            if ("SetNextAVTransportURI".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"NextURI", "in", "AVTransportURI"},
                        {"NextURIMetaData", "in", "AVTransportURIMetaData"}};
            }
            if ("GetMediaInfo".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"NrTracks", "out", "NumberOfTracks"},
                        {"MediaDuration", "out", "CurrentTrackDuration"},
                        {"CurrentURI", "out", "AVTransportURI"},
                        {"CurrentURIMetaData", "out", "AVTransportURIMetaData"},
                        {"NextURI", "out", "AVTransportURI"},
                        {"NextURIMetaData", "out", "AVTransportURIMetaData"},
                        {"PlayMedium", "out", "PlaybackStorageMedium"}};
            }
            if ("GetTransportInfo".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"CurrentTransportState", "out", "TransportState"},
                        {"CurrentTransportStatus", "out", "TransportStatus"},
                        {"CurrentSpeed", "out", "TransportState"}};
            }
            if ("GetPositionInfo".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"Track", "out", "CurrentTrack"},
                        {"TrackDuration", "out", "CurrentTrackDuration"},
                        {"TrackMetaData", "out", "CurrentTrackMetaData"},
                        {"TrackURI", "out", "CurrentTrackURI"},
                        {"RelTime", "out", "CurrentTrackDuration"},
                        {"AbsTime", "out", "CurrentTrackDuration"},
                        {"RelCount", "out", "CurrentTrack"},
                        {"AbsCount", "out", "CurrentTrack"}};
            }
            if ("Seek".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"Unit", "in", "TransportState"},
                        {"Target", "in", "TransportState"}};
            }
            if ("Play".equals(action) || "Pause".equals(action) || "Stop".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"Speed", "in", "TransportState"}};
            }
            if ("GetTransportSettings".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"PlayMode", "out", "TransportState"},
                        {"RecQualityMode", "out", "TransportState"}};
            }
            if ("GetDeviceCapabilities".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"PlayMedia", "out", "PossiblePlaybackStorageMedia"},
                        {"RecMedia", "out", "PossiblePlaybackStorageMedia"},
                        {"RecQualityModes", "out", "PossiblePlaybackStorageMedia"}};
            }
            if ("GetCurrentTransportActions".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"Actions", "out", "CurrentTransportActions"}};
            }
            return new String[][]{{"InstanceID", "in", "TransportState"}};
        }
        if (SERVICE_RENDERING_CONTROL.equals(serviceType)) {
            if ("GetVolume".equals(action) || "SetVolume".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"Channel", "in", "PresetNameList"},
                        {"DesiredVolume", "in", "Volume"},
                        {"CurrentVolume", "out", "Volume"}};
            }
            if ("GetMute".equals(action) || "SetMute".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"Channel", "in", "PresetNameList"},
                        {"DesiredMute", "in", "Mute"},
                        {"CurrentMute", "out", "Mute"}};
            }
            if ("ListPresets".equals(action)) {
                return new String[][]{{"InstanceID", "in", "TransportState"},
                        {"CurrentPresetNameList", "out", "PresetNameList"}};
            }
            return new String[][]{{"InstanceID", "in", "TransportState"}};
        }
        if ("GetCurrentConnectionIDs".equals(action)) {
            return new String[][]{{"ConnectionIDs", "out", "CurrentConnectionIDs"}};
        }
        if ("GetCurrentConnectionInfo".equals(action)) {
            return new String[][]{{"ConnectionID", "in", "CurrentConnectionIDs"}};
        }
        return new String[][]{{"Source", "out", "SourceProtocolInfo"},
                {"Sink", "out", "SinkProtocolInfo"}};
    }

    /** What this renderer can play, advertised through ConnectionManager. */
    public static String sinkProtocolInfo() {
        StringBuilder list = new StringBuilder();
        String[] mimeTypes = {"video/mp4", "video/x-matroska", "video/mpeg", "video/webm",
                "video/x-msvideo", "video/quicktime",
                "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpeg",
                "audio/mp4", "audio/aac", "audio/flac", "audio/ogg", "audio/wav"};
        for (String mime : mimeTypes) {
            if (list.length() > 0) list.append(',');
            list.append("http-get:*:").append(mime).append(":*");
        }
        return list.toString();
    }

    /** SSDP search response for an M-SEARCH of the renderer type. */
    public static String searchResponse(Device device, String location, String searchTarget,
                                       int maxAgeSeconds) {
        StringBuilder message = new StringBuilder();
        message.append("HTTP/1.1 200 OK\r\n");
        message.append("CACHE-CONTROL: max-age=").append(maxAgeSeconds).append("\r\n");
        message.append("DATE: ").append(httpDate()).append("\r\n");
        message.append("EXT:\r\n");
        message.append("LOCATION: ").append(location).append("\r\n");
        message.append("SERVER: ").append(serverHeader()).append("\r\n");
        message.append("ST: ").append(searchTarget).append("\r\n");
        message.append("USN: ").append(device.udn());
        if (!"ssdp:all".equals(searchTarget) && !"upnp:rootdevice".equals(searchTarget)) {
            message.append("::").append(searchTarget);
        }
        message.append("\r\n\r\n");
        return message.toString();
    }

    /** Periodic {@code ssdp:alive} announcement. */
    public static String alive(String deviceType, String usn, String location, int maxAgeSeconds) {
        StringBuilder message = new StringBuilder();
        message.append("NOTIFY * HTTP/1.1\r\n");
        message.append("HOST: 239.255.255.250:1900\r\n");
        message.append("CACHE-CONTROL: max-age=").append(maxAgeSeconds).append("\r\n");
        message.append("LOCATION: ").append(location).append("\r\n");
        message.append("NT: ").append(deviceType).append("\r\n");
        message.append("NTS: ssdp:alive\r\n");
        message.append("SERVER: ").append(serverHeader()).append("\r\n");
        message.append("USN: ").append(usn).append("\r\n\r\n");
        return message.toString();
    }

    /** {@code ssdp:byebye} on shutdown, so control points drop the device immediately. */
    public static String byebye(String deviceType, String usn) {
        return "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                + "NT: " + deviceType + "\r\nNTS: ssdp:byebye\r\n"
                + "USN: " + usn + "\r\n\r\n";
    }

    public static String serverHeader() {
        return "Android/" + android.os.Build.VERSION.SDK_INT + " UPnP/1.0 NukaCast/1.0";
    }

    private static String httpDate() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return format.format(new java.util.Date());
    }
}
