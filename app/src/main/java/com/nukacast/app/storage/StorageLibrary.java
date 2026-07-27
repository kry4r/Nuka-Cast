package com.nukacast.app.storage;

import android.content.Context;
import android.net.Uri;
import android.util.Xml;

import com.nukacast.app.core.NukaRuntime;
import com.nukacast.app.net.HttpStack;
import com.nukacast.app.storage.model.MediaEntry;
import com.nukacast.app.storage.model.StorageMount;
import com.nukacast.app.tvbox.model.MediaDetail;
import com.nukacast.app.tvbox.model.PlaybackInfo;
import com.nukacast.app.tvbox.model.SearchItem;
import com.nukacast.app.tvbox.model.SearchQuery;
import com.nukacast.app.util.Digests;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class StorageLibrary {
    public interface ScanListener {
        void onComplete(int mounts, int files);
    }

    private static final int MAX_FILES = 5000;
    private static final int MAX_DIRECTORIES = 600;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/>"
            + "<d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>";

    private final StorageStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<StorageMount> mounts;
    private final List<MediaEntry> entries;
    private volatile boolean scanning;

    public StorageLibrary(Context context) {
        store = new StorageStore(context);
        mounts = store.loadMounts();
        entries = store.loadIndex();
    }

    public synchronized List<StorageMount> mounts() {
        List<StorageMount> values = new ArrayList<StorageMount>();
        for (StorageMount mount : mounts) values.add(mount.publicView());
        return values;
    }

    public synchronized StorageMount add(String name, String type, String uri,
                                         String username, String password) {
        StorageMount mount = new StorageMount();
        mount.id = UUID.randomUUID().toString();
        mount.name = required(name, "名称");
        mount.type = normalizeType(type);
        mount.uri = normalizeUri(mount.type, required(uri, "地址"));
        mount.username = safe(username);
        mount.password = safe(password);
        mounts.add(mount);
        store.saveMounts(mounts);
        return mount.publicView();
    }

    public synchronized boolean remove(String id) {
        boolean removed = false;
        for (int i = mounts.size() - 1; i >= 0; i--) {
            if (safe(id).equals(mounts.get(i).id)) {
                mounts.remove(i);
                removed = true;
            }
        }
        if (!removed) return false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (safe(id).equals(entries.get(i).mountId)) entries.remove(i);
        }
        store.saveMounts(mounts);
        store.saveIndex(entries);
        return true;
    }

    public boolean isScanning() { return scanning; }

    public void scanAllAsync(final ScanListener listener) {
        if (scanning) return;
        scanning = true;
        executor.execute(new Runnable() {
            @Override public void run() {
                int count = 0;
                try { count = scanAll(); } finally { scanning = false; }
                if (listener != null) listener.onComplete(mountCount(), count);
            }
        });
    }

    public synchronized List<MediaEntry> entries() {
        return new ArrayList<MediaEntry>(entries);
    }

    public synchronized List<SearchItem> home(int limit) {
        List<MediaEntry> sorted = new ArrayList<MediaEntry>(entries);
        Collections.sort(sorted, new Comparator<MediaEntry>() {
            @Override public int compare(MediaEntry left, MediaEntry right) {
                return left.modifiedAt == right.modifiedAt ? 0 : (left.modifiedAt > right.modifiedAt ? -1 : 1);
            }
        });
        List<SearchItem> values = new ArrayList<SearchItem>();
        Set<String> seen = new HashSet<String>();
        for (MediaEntry entry : sorted) {
            String key = entry.mountId + "|" + entry.title + "|" + entry.year;
            if (!seen.add(key)) continue;
            values.add(entry.toSearchItem());
            if (values.size() >= Math.max(1, limit)) break;
        }
        return values;
    }

    public synchronized List<SearchItem> search(SearchQuery query) {
        List<SearchItem> result = new ArrayList<SearchItem>();
        String keyword = safe(query.keyword).toLowerCase(Locale.CHINA);
        for (MediaEntry entry : entries) {
            if (!entry.title.toLowerCase(Locale.CHINA).contains(keyword)
                    && !entry.fileName.toLowerCase(Locale.CHINA).contains(keyword)) continue;
            if (!safe(query.contentType).isEmpty() && !entry.typeName.contains(query.contentType)) continue;
            if (!safe(query.year).isEmpty() && !entry.year.equals(query.year)) continue;
            result.add(entry.toSearchItem());
            if (result.size() >= Math.max(1, query.pageSize)) break;
        }
        return result;
    }

    public synchronized MediaDetail detail(String id) {
        MediaEntry selected = requireEntry(id);
        MediaDetail detail = new MediaDetail();
        SearchItem base = selected.toSearchItem();
        detail.sourceId = base.sourceId;
        detail.siteKey = base.siteKey;
        detail.siteName = base.siteName;
        detail.vodId = base.vodId;
        detail.name = base.name;
        detail.poster = base.poster;
        detail.remarks = base.remarks;
        detail.year = base.year;
        detail.typeName = base.typeName;
        detail.plot = selected.fileName;
        MediaDetail.PlaySource source = new MediaDetail.PlaySource();
        source.name = "片库";
        List<MediaEntry> related = new ArrayList<MediaEntry>();
        for (MediaEntry entry : entries) {
            if (entry.mountId.equals(selected.mountId) && entry.title.equals(selected.title)) related.add(entry);
        }
        Collections.sort(related, ENTRY_ORDER);
        for (MediaEntry entry : related) {
            MediaDetail.Episode episode = new MediaDetail.Episode();
            episode.id = entry.id;
            episode.name = entry.episode > 0 ? "第 " + entry.episode + " 集" : "播放";
            source.episodes.add(episode);
        }
        detail.playSources.add(source);
        return detail;
    }

    public synchronized PlaybackInfo resolve(String id, String title) {
        MediaEntry entry = requireEntry(id);
        StorageMount mount = requireMount(entry.mountId);
        PlaybackInfo info = new PlaybackInfo();
        info.siteKey = "storage";
        info.title = safe(title).isEmpty() ? entry.title : title;
        if (StorageMount.TYPE_LOCAL.equals(mount.type)) {
            info.url = Uri.fromFile(new File(entry.uri)).toString();
        } else if (StorageMount.TYPE_WEBDAV.equals(mount.type)) {
            info.url = entry.uri;
            if (!mount.username.isEmpty()) info.headers.put("Authorization",
                    Credentials.basic(mount.username, mount.password));
        } else {
            info.url = "http://127.0.0.1:" + NukaRuntime.CONTROL_PORT + "/media/" + entry.id;
        }
        info.direct = true;
        return info;
    }

    public synchronized MediaStream openSmb(String id, long offset) throws Exception {
        MediaEntry entry = requireEntry(id);
        StorageMount mount = requireMount(entry.mountId);
        if (!StorageMount.TYPE_SMB.equals(mount.type)) throw new IllegalArgumentException("不是 SMB 媒体");
        SmbFile file = new SmbFile(entry.uri, auth(mount));
        InputStream input = file.getInputStream();
        long remaining = Math.max(0, file.length() - Math.max(0, offset));
        long skipped = 0;
        while (skipped < offset) {
            long value = input.skip(offset - skipped);
            if (value <= 0) break;
            skipped += value;
        }
        return new MediaStream(input, file.length(), remaining, mime(entry.fileName));
    }

    public void shutdown() { executor.shutdownNow(); }

    private int scanAll() {
        List<StorageMount> current;
        List<MediaEntry> old;
        synchronized (this) {
            current = new ArrayList<StorageMount>(mounts);
            old = new ArrayList<MediaEntry>(entries);
        }
        List<MediaEntry> scanned = new ArrayList<MediaEntry>();
        for (StorageMount mount : current) {
            if (!mount.enabled) continue;
            try {
                List<MediaEntry> values = scan(mount);
                scanned.addAll(values);
                mount.fileCount = values.size();
                mount.lastScanAt = System.currentTimeMillis();
                mount.error = "";
            } catch (Exception error) {
                mount.error = message(error);
                for (MediaEntry entry : old) if (entry.mountId.equals(mount.id)) scanned.add(entry);
            }
        }
        synchronized (this) {
            entries.clear();
            entries.addAll(scanned);
            store.saveMounts(mounts);
            store.saveIndex(entries);
            return entries.size();
        }
    }

    private List<MediaEntry> scan(StorageMount mount) throws Exception {
        if (StorageMount.TYPE_LOCAL.equals(mount.type)) return scanLocal(mount);
        if (StorageMount.TYPE_WEBDAV.equals(mount.type)) return scanWebDav(mount);
        return scanSmb(mount);
    }

    private List<MediaEntry> scanLocal(StorageMount mount) {
        List<MediaEntry> result = new ArrayList<MediaEntry>();
        ArrayDeque<File> pending = new ArrayDeque<File>();
        pending.add(new File(mount.uri));
        int directories = 0;
        while (!pending.isEmpty() && result.size() < MAX_FILES && directories++ < MAX_DIRECTORIES) {
            File directory = pending.removeFirst();
            File[] files = directory.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (file.isDirectory()) pending.addLast(file);
                else if (MediaNameParser.isVideo(file.getName())) {
                    result.add(entry(mount, file.getName(), file.getAbsolutePath(),
                            localPoster(file), file.length(), file.lastModified()));
                    if (result.size() >= MAX_FILES) break;
                }
            }
        }
        return result;
    }

    private List<MediaEntry> scanSmb(StorageMount mount) throws Exception {
        List<MediaEntry> result = new ArrayList<MediaEntry>();
        ArrayDeque<SmbFile> pending = new ArrayDeque<SmbFile>();
        pending.add(new SmbFile(mount.uri, auth(mount)));
        int directories = 0;
        while (!pending.isEmpty() && result.size() < MAX_FILES && directories++ < MAX_DIRECTORIES) {
            SmbFile directory = pending.removeFirst();
            SmbFile[] files = directory.listFiles();
            if (files == null) continue;
            for (SmbFile file : files) {
                if (file.isDirectory()) pending.addLast(file);
                else if (MediaNameParser.isVideo(file.getName())) {
                    result.add(entry(mount, file.getName(), file.getPath(), "",
                            file.length(), file.getLastModified()));
                    if (result.size() >= MAX_FILES) break;
                }
            }
        }
        return result;
    }

    private List<MediaEntry> scanWebDav(StorageMount mount) throws Exception {
        List<DavResource> resources = new ArrayList<DavResource>();
        ArrayDeque<String> pending = new ArrayDeque<String>();
        Set<String> visited = new HashSet<String>();
        pending.add(mount.uri);
        int directories = 0;
        while (!pending.isEmpty() && resources.size() < MAX_FILES * 2
                && directories++ < MAX_DIRECTORIES) {
            String directory = pending.removeFirst();
            if (!visited.add(directory)) continue;
            for (DavResource resource : listWebDav(mount, directory)) {
                if (sameResource(directory, resource.href)) continue;
                resources.add(resource);
                if (resource.collection) pending.addLast(ensureSlash(resource.href));
            }
        }
        Map<String, String> artwork = new HashMap<String, String>();
        for (DavResource resource : resources) {
            if (resource.collection) continue;
            String lower = name(resource.href).toLowerCase(Locale.US);
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                artwork.put(imageKey(resource.href), resource.href);
            }
        }
        List<MediaEntry> result = new ArrayList<MediaEntry>();
        for (DavResource resource : resources) {
            String fileName = name(resource.href);
            if (!resource.collection && MediaNameParser.isVideo(fileName)) {
                String poster = artwork.get(imageKey(resource.href));
                if (poster == null) poster = artwork.get(fallbackImageKey(resource.href));
                result.add(entry(mount, fileName, resource.href, poster == null ? "" : poster,
                        resource.size, resource.modifiedAt));
                if (result.size() >= MAX_FILES) break;
            }
        }
        return result;
    }

    private List<DavResource> listWebDav(StorageMount mount, String uri) throws Exception {
        RequestBody body = RequestBody.create(MediaType.parse("application/xml; charset=utf-8"), PROPFIND_BODY);
        Request.Builder builder = new Request.Builder().url(uri).method("PROPFIND", body)
                .header("Depth", "1").header("User-Agent", "NukaCast/0.2");
        if (!mount.username.isEmpty()) builder.header("Authorization",
                Credentials.basic(mount.username, mount.password));
        try (Response response = HttpStack.client().newCall(builder.build()).execute()) {
            if ((response.code() != 207 && !response.isSuccessful()) || response.body() == null) {
                throw new IllegalStateException("WebDAV HTTP " + response.code());
            }
            return parseDav(uri, response.body().string());
        }
    }

    private List<DavResource> parseDav(String base, String xml) throws Exception {
        List<DavResource> result = new ArrayList<DavResource>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        DavResource current = null;
        String tag = "";
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                tag = parser.getName().toLowerCase(Locale.US);
                if ("response".equals(tag)) current = new DavResource();
                if (current != null && "collection".equals(tag)) current.collection = true;
            } else if (event == XmlPullParser.TEXT && current != null) {
                String value = parser.getText() == null ? "" : parser.getText().trim();
                if ("href".equals(tag)) current.href = URI.create(base).resolve(value).toString();
                else if ("getcontentlength".equals(tag)) current.size = number(value);
                else if ("getlastmodified".equals(tag)) current.modifiedAt = date(value);
            } else if (event == XmlPullParser.END_TAG) {
                String ended = parser.getName().toLowerCase(Locale.US);
                if ("response".equals(ended) && current != null && !current.href.isEmpty()) {
                    result.add(current);
                    current = null;
                }
                tag = "";
            }
        }
        return result;
    }

    private MediaEntry entry(StorageMount mount, String fileName, String uri, String poster,
                             long size, long modifiedAt) {
        MediaNameParser.ParsedName parsed = MediaNameParser.parse(fileName);
        MediaEntry entry = new MediaEntry();
        entry.id = Digests.sha256((mount.id + "|" + uri).getBytes(UTF_8)).substring(0, 24);
        entry.mountId = mount.id;
        entry.mountName = mount.name;
        entry.title = parsed.title;
        entry.fileName = fileName;
        entry.uri = uri;
        entry.poster = safe(poster);
        entry.typeName = parsed.typeName;
        entry.year = parsed.year;
        entry.season = parsed.season;
        entry.episode = parsed.episode;
        entry.size = size;
        entry.modifiedAt = modifiedAt;
        return entry;
    }

    private synchronized int mountCount() { return mounts.size(); }

    private synchronized MediaEntry requireEntry(String id) {
        for (MediaEntry entry : entries) if (entry.id.equals(id)) return entry;
        throw new IllegalArgumentException("找不到片库条目");
    }

    private synchronized StorageMount requireMount(String id) {
        for (StorageMount mount : mounts) if (mount.id.equals(id)) return mount;
        throw new IllegalArgumentException("找不到存储挂载");
    }

    private static NtlmPasswordAuthentication auth(StorageMount mount) {
        return new NtlmPasswordAuthentication(null, mount.username, mount.password);
    }

    private static String localPoster(File video) {
        String name = video.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String[] candidates = {stem + ".jpg", stem + ".png", "poster.jpg", "folder.jpg"};
        File parent = video.getParentFile();
        if (parent == null) return "";
        for (String candidate : candidates) {
            File image = new File(parent, candidate);
            if (image.isFile()) return Uri.fromFile(image).toString();
        }
        return "";
    }

    private static String imageKey(String uri) {
        int slash = uri.lastIndexOf('/');
        String directory = slash < 0 ? "" : uri.substring(0, slash + 1);
        String file = slash < 0 ? uri : uri.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        String lower = stem.toLowerCase(Locale.US);
        if ("poster".equals(lower) || "folder".equals(lower)) return directory + "*";
        return directory + lower;
    }

    private static String fallbackImageKey(String uri) {
        int slash = uri.lastIndexOf('/');
        return (slash < 0 ? "" : uri.substring(0, slash + 1)) + "*";
    }

    private static String name(String uri) {
        try {
            String value = uri.substring(uri.lastIndexOf('/') + 1);
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception ignored) {
            return uri.substring(uri.lastIndexOf('/') + 1);
        }
    }

    private static boolean sameResource(String left, String right) {
        return ensureSlash(left).equalsIgnoreCase(ensureSlash(right));
    }

    private static String ensureSlash(String value) { return value.endsWith("/") ? value : value + "/"; }

    private static String normalizeType(String type) {
        String value = safe(type).toLowerCase(Locale.US);
        if (!StorageMount.TYPE_LOCAL.equals(value) && !StorageMount.TYPE_WEBDAV.equals(value)
                && !StorageMount.TYPE_SMB.equals(value)) throw new IllegalArgumentException("不支持的挂载类型");
        return value;
    }

    private static String normalizeUri(String type, String uri) {
        String value = uri.trim();
        if (StorageMount.TYPE_LOCAL.equals(type)) return value.startsWith("file://")
                ? Uri.parse(value).getPath() : value;
        if (StorageMount.TYPE_SMB.equals(type) && !value.startsWith("smb://")) {
            throw new IllegalArgumentException("SMB 地址必须以 smb:// 开头");
        }
        if (StorageMount.TYPE_WEBDAV.equals(type)
                && !value.startsWith("http://") && !value.startsWith("https://")) {
            throw new IllegalArgumentException("WebDAV 地址必须使用 HTTP 或 HTTPS");
        }
        return ensureSlash(value);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(field + "不能为空");
        return value.trim();
    }

    private static String mime(String name) {
        String value = safe(name).toLowerCase(Locale.US);
        if (value.endsWith(".mkv")) return "video/x-matroska";
        if (value.endsWith(".webm")) return "video/webm";
        if (value.endsWith(".ts") || value.endsWith(".m2ts")) return "video/mp2t";
        return "video/mp4";
    }

    private static long number(String value) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return 0; }
    }

    private static long date(String value) {
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEEE, dd-MMM-yy HH:mm:ss zzz",
                "EEE MMM d HH:mm:ss yyyy"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                format.setTimeZone(TimeZone.getTimeZone("GMT"));
                return format.parse(value).getTime();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static final Comparator<MediaEntry> ENTRY_ORDER = new Comparator<MediaEntry>() {
        @Override public int compare(MediaEntry left, MediaEntry right) {
            if (left.season != right.season) return left.season - right.season;
            if (left.episode != right.episode) return left.episode - right.episode;
            return left.fileName.compareToIgnoreCase(right.fileName);
        }
    };

    private static final class DavResource {
        String href = "";
        boolean collection;
        long size;
        long modifiedAt;
    }

    public static final class MediaStream {
        public final InputStream input;
        public final long totalLength;
        public final long remainingLength;
        public final String mime;

        MediaStream(InputStream input, long totalLength, long remainingLength, String mime) {
            this.input = input;
            this.totalLength = totalLength;
            this.remainingLength = remainingLength;
            this.mime = mime;
        }
    }
}
