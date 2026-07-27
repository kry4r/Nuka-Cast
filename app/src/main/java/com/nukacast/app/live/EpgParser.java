package com.nukacast.app.live;

import android.util.Xml;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nukacast.app.live.model.EpgSchedule;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;

public final class EpgParser {
    private EpgParser() {}

    public static EpgSchedule parse(String body, String channel, String date) throws Exception {
        if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("节目单为空");
        return body.trim().startsWith("<") ? parseXml(body, channel, date) : parseJson(body, channel, date);
    }

    private static EpgSchedule parseJson(String body, String channel, String date) {
        JsonElement root = new JsonParser().parse(body);
        EpgSchedule result = schedule(channel, date);
        JsonArray programs = null;
        if (root.isJsonArray()) programs = root.getAsJsonArray();
        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            result.channel = string(object, "channel_name", result.channel);
            for (String key : new String[] { "epg_data", "data", "programs", "list" }) {
                JsonElement value = object.get(key);
                if (value != null && value.isJsonArray()) { programs = value.getAsJsonArray(); break; }
            }
        }
        if (programs == null) return result;
        for (JsonElement item : programs) {
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            EpgSchedule.Program program = new EpgSchedule.Program();
            program.title = string(object, "title", string(object, "name", "节目"));
            program.start = string(object, "start", string(object, "startTime", ""));
            program.end = string(object, "end", string(object, "endTime", ""));
            program.description = string(object, "desc", string(object, "description", ""));
            result.programs.add(program);
        }
        return result;
    }

    private static EpgSchedule parseXml(String body, String channel, String date) throws Exception {
        EpgSchedule result = schedule(channel, date);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(body));
        EpgSchedule.Program current = null;
        boolean accepted = false;
        String tag = "";
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                tag = parser.getName().toLowerCase();
                if ("programme".equals(tag)) {
                    String itemChannel = safe(parser.getAttributeValue(null, "channel"));
                    accepted = channel == null || channel.isEmpty() || itemChannel.isEmpty()
                            || normalize(itemChannel).equals(normalize(channel));
                    current = accepted ? new EpgSchedule.Program() : null;
                    if (current != null) {
                        current.start = compactTime(parser.getAttributeValue(null, "start"));
                        current.end = compactTime(parser.getAttributeValue(null, "stop"));
                    }
                }
            } else if (event == XmlPullParser.TEXT && current != null) {
                String text = safe(parser.getText()).trim();
                if ("title".equals(tag)) current.title += text;
                else if ("desc".equals(tag)) current.description += text;
            } else if (event == XmlPullParser.END_TAG) {
                if ("programme".equalsIgnoreCase(parser.getName()) && current != null) {
                    result.programs.add(current);
                    current = null;
                }
                tag = "";
            }
        }
        return result;
    }

    private static EpgSchedule schedule(String channel, String date) {
        EpgSchedule result = new EpgSchedule();
        result.channel = safe(channel);
        result.date = safe(date);
        return result;
    }

    private static String compactTime(String value) {
        String safe = safe(value).trim();
        if (safe.length() >= 12) return safe.substring(8, 10) + ":" + safe.substring(10, 12);
        return safe;
    }

    private static String normalize(String value) {
        return safe(value).toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
