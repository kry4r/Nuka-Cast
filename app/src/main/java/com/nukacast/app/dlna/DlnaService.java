package com.nukacast.app.dlna;

import com.nukacast.app.diagnostics.AppLog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The AVTransport / RenderingControl / ConnectionManager actions.
 *
 * <p>Turns SOAP requests into {@link DlnaRenderer} calls and builds the replies. Separated from HTTP
 * and from the player so the behaviour a control point depends on can be tested directly.
 */
public final class DlnaService {
    /** Result of one action: a body to return, plus the HTTP status it needs. */
    public static final class Result {
        public final int status;
        public final byte[] body;
        public final String contentType;

        Result(int status, byte[] body, String contentType) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
        }

        public static Result ok(String serviceType, String action, Map<String, String> out) {
            return new Result(200, SoapMessage.response(serviceType, action, out),
                    "text/xml; charset=\"utf-8\"");
        }

        public static Result fault(int status, int errorCode, String description) {
            return new Result(status, SoapMessage.fault("", "", errorCode, description),
                    "text/xml; charset=\"utf-8\"");
        }
    }

    private final DlnaRenderer renderer;

    public DlnaService(DlnaRenderer renderer) {
        this.renderer = renderer;
    }

    public DlnaRenderer renderer() {
        return renderer;
    }

    /** Handles a parsed request; never throws, so a malformed request cannot kill the server. */
    public Result handle(SoapMessage request) {
        if (request == null) return Result.fault(400, 402, "Invalid Action");
        try {
            return dispatch(request);
        } catch (Throwable error) {
            AppLog.w("投屏", "DLNA 动作失败 " + request.action + "："
                    + error.getClass().getSimpleName());
            return Result.fault(500, 501, "Action Failed");
        }
    }

    private Result dispatch(SoapMessage request) {
        String action = request.action;
        String service = request.serviceType;

        if ("SetAVTransportURI".equals(action)) {
            String uri = request.argument("CurrentURI");
            if (uri.isEmpty()) return Result.fault(500, 402, "Invalid Args");
            if (!isSupportedUri(uri)) {
                return Result.fault(500, 714, "Illegal MIME-type");
            }
            renderer.setUri(uri, request.argument("CurrentURIMetaData"));
            return Result.ok(service, action, null);
        }
        if ("SetNextAVTransportURI".equals(action)) {
            // Accepted and remembered, but not implemented as auto-advance: the queue is the control
            // point's job, and pretending otherwise would silently drop the second item.
            return Result.ok(service, action, null);
        }
        if ("Play".equals(action)) {
            // Play resumes a loaded transport and reloads anything else; forcing a reload here turned
            // Pause → Play into a restart from the beginning.
            renderer.play();
            return Result.ok(service, action, null);
        }
        if ("Pause".equals(action)) {
            renderer.pause();
            return Result.ok(service, action, null);
        }
        if ("Stop".equals(action)) {
            renderer.stop();
            return Result.ok(service, action, null);
        }
        if ("Seek".equals(action)) {
            String unit = request.argument("Unit").toUpperCase(java.util.Locale.ROOT);
            String target = request.argument("Target");
            if ("REL_TIME".equals(unit)) {
                renderer.seekTo(parseTime(target));
            } else if ("TRACK_NR".equals(unit)) {
                return Result.fault(500, 710, "Seek mode not supported");
            } else {
                return Result.fault(500, 710, "Seek mode not supported");
            }
            return Result.ok(service, action, null);
        }
        if ("GetTransportInfo".equals(action)) {
            return Result.ok(service, action, new LinkedHashMap<String, String>() {{
                put("CurrentTransportState", renderer.state());
                put("CurrentTransportStatus", "OK");
                put("CurrentSpeed", "1");
            }});
        }
        if ("GetMediaInfo".equals(action)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("NrTracks", renderer.currentUri().isEmpty() ? "0" : "1");
            // With nothing loaded a leftover duration from the last session is a lie; control points
            // show it as the length of an empty player.
            out.put("MediaDuration", DlnaRenderer.formatTime(
                    renderer.loaded() ? renderer.durationMs() : 0));
            out.put("CurrentURI", renderer.currentUri());
            out.put("CurrentURIMetaData", renderer.currentMetadata());
            out.put("NextURI", "");
            out.put("NextURIMetaData", "");
            out.put("PlayMedium", "NETWORK");
            out.put("RecordMedium", "NOT_IMPLEMENTED");
            out.put("WriteStatus", "NOT_IMPLEMENTED");
            return Result.ok(service, action, out);
        }
        if ("GetPositionInfo".equals(action)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("Track", renderer.currentUri().isEmpty() ? "0" : "1");
            out.put("TrackDuration", DlnaRenderer.formatTime(
                    renderer.loaded() ? renderer.durationMs() : 0));
            out.put("TrackMetaData", renderer.currentMetadata());
            out.put("TrackURI", renderer.currentUri());
            out.put("RelTime", DlnaRenderer.formatTime(renderer.positionMs()));
            out.put("AbsTime", DlnaRenderer.formatTime(renderer.positionMs()));
            out.put("RelCount", "0");
            out.put("AbsCount", "2147483647");
            return Result.ok(service, action, out);
        }
        if ("GetTransportSettings".equals(action)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("PlayMode", "NORMAL");
            out.put("RecQualityMode", "NOT_IMPLEMENTED");
            return Result.ok(service, action, out);
        }
        if ("GetDeviceCapabilities".equals(action)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("PlayMedia", "NETWORK");
            out.put("RecMedia", "NOT_IMPLEMENTED");
            out.put("RecQualityModes", "NOT_IMPLEMENTED");
            return Result.ok(service, action, out);
        }
        if ("GetCurrentTransportActions".equals(action)) {
            String actions = renderer.currentUri().isEmpty()
                    ? "Stop"
                    : (DlnaRenderer.PLAYING.equals(renderer.state())
                        ? "Stop,Pause,Seek,Play" : "Stop,Play,Seek");
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("Actions", actions);
            return Result.ok(service, action, out);
        }
        if ("Next".equals(action) || "Previous".equals(action)) {
            return Result.fault(500, 701, "Transition not available");
        }
        if ("GetVolume".equals(action)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("CurrentVolume", String.valueOf(renderer.volume()));
            return Result.ok(service, action, out);
        }
        if ("SetVolume".equals(action)) {
            String desired = request.argument("DesiredVolume");
            try {
                renderer.setVolume(Integer.parseInt(desired.trim()));
            } catch (NumberFormatException error) {
                return Result.fault(500, 402, "Invalid Args");
            }
            return Result.ok(service, action, null);
        }
        if ("GetMute".equals(action)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("CurrentMute", renderer.muted() ? "1" : "0");
            return Result.ok(service, action, out);
        }
        if ("SetMute".equals(action)) {
            String desired = request.argument("DesiredMute").trim();
            renderer.setMuted("1".equals(desired) || "true".equalsIgnoreCase(desired));
            return Result.ok(service, action, null);
        }
        if ("ListPresets".equals(action)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("CurrentPresetNameList", "FactoryDefaults");
            return Result.ok(service, action, out);
        }
        if ("SelectPreset".equals(action)) {
            return Result.ok(service, action, null);
        }
        if ("GetProtocolInfo".equals(action)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("Source", "");
            out.put("Sink", protocolInfoWithAlternatives());
            return Result.ok(service, action, out);
        }
        if ("GetCurrentConnectionIDs".equals(action)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("ConnectionIDs", "0");
            return Result.ok(service, action, out);
        }
        if ("GetCurrentConnectionInfo".equals(action)) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put("RcsID", "0");
            out.put("AVTransportID", "0");
            out.put("ProtocolInfo", "");
            out.put("PeerConnectionManager", "");
            out.put("PeerConnectionID", "-1");
            out.put("Direction", "Input");
            out.put("Status", "OK");
            return Result.ok(service, action, out);
        }
        return Result.fault(500, 401, "Invalid Action");
    }

    /**
     * Whether the renderer advertises support for a URI.
     *
     * <p>Only http(s) is accepted: the rest of the app's players fetch over HTTP, and a control point
     * sending a local file path would otherwise produce a silent failure.
     */
    static boolean isSupportedUri(String uri) {
        if (uri == null) return false;
        String lower = uri.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Sink protocols, plus the generic entry some control points look for before anything else. */
    static String protocolInfoWithAlternatives() {
        return DlnaDescription.sinkProtocolInfo();
    }

    /** Parses {@code H:MM:SS}, {@code MM:SS} or {@code H:MM:SS.mmm}. */
    public static int parseTime(String value) {
        if (value == null) return 0;
        String text = value.trim();
        if (text.isEmpty()) return 0;
        int dot = text.indexOf('.');
        if (dot >= 0) text = text.substring(0, dot);
        String[] parts = text.split(":");
        try {
            int seconds = 0;
            for (String part : parts) seconds = seconds * 60 + Integer.parseInt(part.trim());
            return Math.max(0, seconds * 1000);
        } catch (NumberFormatException error) {
            return 0;
        }
    }
}
