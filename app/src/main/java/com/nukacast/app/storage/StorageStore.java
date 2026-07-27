package com.nukacast.app.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.nukacast.app.storage.model.MediaEntry;
import com.nukacast.app.storage.model.StorageMount;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

final class StorageStore {
    private static final String PREFS = "nukacast_storage";
    private static final String MOUNTS = "mounts";
    private static final String INDEX_FILE = "storage-index.json";
    private static final Type MOUNT_LIST = new TypeToken<List<StorageMount>>() {}.getType();
    private static final Type MEDIA_LIST = new TypeToken<List<MediaEntry>>() {}.getType();

    private final Context context;
    private final SharedPreferences preferences;
    private final Gson gson = new Gson();

    StorageStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized List<StorageMount> loadMounts() {
        try {
            List<StorageMount> values = gson.fromJson(preferences.getString(MOUNTS, "[]"), MOUNT_LIST);
            return values == null ? new ArrayList<StorageMount>() : values;
        } catch (Exception ignored) {
            return new ArrayList<StorageMount>();
        }
    }

    synchronized void saveMounts(List<StorageMount> mounts) {
        preferences.edit().putString(MOUNTS, gson.toJson(mounts, MOUNT_LIST)).apply();
    }

    synchronized List<MediaEntry> loadIndex() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    context.openFileInput(INDEX_FILE), "UTF-8"));
            List<MediaEntry> values = gson.fromJson(reader, MEDIA_LIST);
            return values == null ? new ArrayList<MediaEntry>() : values;
        } catch (FileNotFoundException ignored) {
            return new ArrayList<MediaEntry>();
        } catch (Exception ignored) {
            return new ArrayList<MediaEntry>();
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
        }
    }

    synchronized void saveIndex(List<MediaEntry> entries) {
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(context.openFileOutput(
                    INDEX_FILE, Context.MODE_PRIVATE), "UTF-8");
            gson.toJson(entries, MEDIA_LIST, writer);
        } catch (Exception error) {
            throw new IllegalStateException("无法保存片库索引", error);
        } finally {
            if (writer != null) try { writer.close(); } catch (Exception ignored) {}
        }
    }
}
