package com.nukacast.app.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AppState {
    public enum ServiceState { STARTING, READY, STREAMING, STOPPED, ERROR }

    public interface Listener {
        void onStateChanged(AppState state);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private volatile ServiceState serviceState = ServiceState.STARTING;
    private volatile String statusMessage = "Starting";
    private volatile String activeMedia = "";
    private volatile int sourceCount;
    private volatile int enabledSiteCount;
    private volatile long stateVersion;
    private volatile long contentVersion;

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public ServiceState getServiceState() {
        return serviceState;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public String getActiveMedia() {
        return activeMedia;
    }

    public int getSourceCount() {
        return sourceCount;
    }

    public int getEnabledSiteCount() {
        return enabledSiteCount;
    }

    public long getStateVersion() {
        return stateVersion;
    }

    public long getContentVersion() {
        return contentVersion;
    }

    public void updateService(ServiceState newState, String message) {
        serviceState = newState;
        statusMessage = message == null ? "" : message;
        changed();
    }

    public void updateActiveMedia(String media) {
        activeMedia = media == null ? "" : media;
        changed();
    }

    public void updateSources(int configs, int sites) {
        sourceCount = configs;
        enabledSiteCount = sites;
        contentVersion++;
        changed();
    }

    public void updateSourceHealth(int configs, int sites) {
        sourceCount = configs;
        enabledSiteCount = sites;
        changed();
    }

    private void changed() {
        stateVersion++;
        for (Listener listener : listeners) {
            listener.onStateChanged(this);
        }
    }
}
