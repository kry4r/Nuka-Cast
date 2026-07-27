#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>

extern "C" {
#include "legacy-airplay/lib/raop.h"
#include "legacy-airplay/lib/stream.h"
#include "legacy-airplay/lib/logger.h"
}

namespace {
JavaVM *g_vm = nullptr;

struct Server {
    raop_t *raop = nullptr;
    jobject bridge = nullptr;
    std::atomic<bool> session_started{false};
};

class EnvScope {
public:
    EnvScope() : env_(nullptr), attached_(false) {
        if (g_vm->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6) != JNI_OK) {
            if (g_vm->AttachCurrentThread(&env_, nullptr) == JNI_OK) attached_ = true;
        }
    }
    ~EnvScope() { if (attached_) g_vm->DetachCurrentThread(); }
    JNIEnv *get() const { return env_; }
private:
    JNIEnv *env_;
    bool attached_;
};

void notify_session(Server *server, bool active) {
    bool previous = server->session_started.exchange(active);
    if (previous == active) return;
    EnvScope scope;
    JNIEnv *env = scope.get();
    if (!env) return;
    jclass type = env->GetObjectClass(server->bridge);
    jmethodID method = env->GetMethodID(type, "onNativeSession", "(Z)V");
    if (method) env->CallVoidMethod(server->bridge, method, active ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(type);
}

void audio_process(void *cls, pcm_data_struct *data) {
    Server *server = static_cast<Server *>(cls);
    notify_session(server, true);
    EnvScope scope;
    JNIEnv *env = scope.get();
    if (!env || !data || !data->data || data->data_len <= 0) return;
    jshortArray samples = env->NewShortArray(data->data_len);
    if (!samples) return;
    env->SetShortArrayRegion(samples, 0, data->data_len,
                             reinterpret_cast<const jshort *>(data->data));
    jclass type = env->GetObjectClass(server->bridge);
    jmethodID method = env->GetMethodID(type, "onNativeAudio", "([SJ)V");
    if (method) env->CallVoidMethod(server->bridge, method, samples, static_cast<jlong>(data->pts));
    env->DeleteLocalRef(type);
    env->DeleteLocalRef(samples);
}

void video_process(void *cls, h264_decode_struct *data) {
    Server *server = static_cast<Server *>(cls);
    notify_session(server, true);
    EnvScope scope;
    JNIEnv *env = scope.get();
    if (!env || !data || !data->data || data->data_len <= 0) return;
    jbyteArray bytes = env->NewByteArray(data->data_len);
    if (!bytes) return;
    env->SetByteArrayRegion(bytes, 0, data->data_len,
                            reinterpret_cast<const jbyte *>(data->data));
    jclass type = env->GetObjectClass(server->bridge);
    jmethodID method = env->GetMethodID(type, "onNativeVideo", "([BIJ)V");
    if (method) env->CallVoidMethod(server->bridge, method, bytes,
                                    static_cast<jint>(data->frame_type),
                                    static_cast<jlong>(data->pts));
    env->DeleteLocalRef(type);
    env->DeleteLocalRef(bytes);
}

void audio_set_volume(void *, void *, float) {}

void log_callback(void *, int level, const char *message) {
    int priority = level <= LOGGER_ERR ? ANDROID_LOG_ERROR
            : level == LOGGER_WARNING ? ANDROID_LOG_WARN
            : level == LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_write(priority, "NukaCast-AirPlay", message ? message : "");
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nukacast_app_airplay_NativeAirPlayBridge_nativeStart(JNIEnv *env, jobject bridge) {
    Server *server = new Server();
    server->bridge = env->NewGlobalRef(bridge);
    raop_callbacks_t callbacks;
    std::memset(&callbacks, 0, sizeof(callbacks));
    callbacks.cls = server;
    callbacks.audio_process = audio_process;
    callbacks.audio_set_volume = audio_set_volume;
    callbacks.video_process = video_process;
    server->raop = raop_init(2, &callbacks);
    if (!server->raop) {
        env->DeleteGlobalRef(server->bridge);
        delete server;
        return 0;
    }
    raop_set_log_callback(server->raop, log_callback, nullptr);
    raop_set_log_level(server->raop, LOGGER_INFO);
    unsigned short port = 0;
    if (raop_start(server->raop, &port) < 0) {
        raop_destroy(server->raop);
        env->DeleteGlobalRef(server->bridge);
        delete server;
        return 0;
    }
    raop_set_port(server->raop, port);
    return reinterpret_cast<jlong>(server);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nukacast_app_airplay_NativeAirPlayBridge_nativeStop(JNIEnv *env, jobject, jlong handle) {
    Server *server = reinterpret_cast<Server *>(handle);
    if (!server) return;
    notify_session(server, false);
    if (server->raop) raop_destroy(server->raop);
    env->DeleteGlobalRef(server->bridge);
    delete server;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nukacast_app_airplay_NativeAirPlayBridge_nativePort(JNIEnv *, jobject, jlong handle) {
    Server *server = reinterpret_cast<Server *>(handle);
    return server && server->raop ? raop_get_port(server->raop) : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nukacast_app_airplay_NativeAirPlayBridge_nativePublicKey(JNIEnv *env, jobject, jlong handle) {
    Server *server = reinterpret_cast<Server *>(handle);
    char output[65] = {0};
    if (!server || !server->raop || raop_get_public_key_hex(server->raop, output, sizeof(output)) < 0) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(output);
}
