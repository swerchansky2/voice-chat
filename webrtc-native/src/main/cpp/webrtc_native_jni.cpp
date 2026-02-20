#include <jni.h>
#include <string>
#include "webrtc_audio.h"

extern "C" JNIEXPORT jint JNICALL Java_com_voicechat_client_native_WebrtcNative_initializeScreenshare(JNIEnv* env, jobject thiz) {
    return AudioManager::instance().initialize();
}

extern "C" JNIEXPORT void JNICALL Java_com_voicechat_client_native_WebrtcNative_shutdownScreenshare(JNIEnv* env, jobject thiz) {
    AudioManager::instance().shutdown();
}

extern "C" JNIEXPORT jint JNICALL Java_com_voicechat_client_native_WebrtcNative_createPeerConnection(JNIEnv* env, jobject thiz) {
    return AudioManager::instance().createPeerConnection();
}

extern "C" JNIEXPORT jint JNICALL Java_com_voicechat_client_native_WebrtcNative_addLocalAudioTrack(JNIEnv* env, jobject thiz, jint peerId, jstring trackId_) {
    const char* trackId = trackId_ ? env->GetStringUTFChars(trackId_, NULL) : NULL;
    int res = AudioManager::instance().addLocalAudioTrack((int)peerId, trackId ? std::string(trackId) : std::string());
    if (trackId) env->ReleaseStringUTFChars(trackId_, trackId);
    return res;
}

extern "C" JNIEXPORT jint JNICALL Java_com_voicechat_client_native_WebrtcNative_startAudioGenerator(JNIEnv* env, jobject thiz, jint peerId) {
    return AudioManager::instance().startAudioGenerator((int)peerId);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_voicechat_client_native_WebrtcNative_createOffer(JNIEnv* env, jobject thiz, jint peerId) {
    std::string sdp = AudioManager::instance().createOffer((int)peerId);
    return env->NewStringUTF(sdp.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_voicechat_client_native_WebrtcNative_createAnswer(JNIEnv* env, jobject thiz, jint peerId) {
    std::string sdp = AudioManager::instance().createAnswer((int)peerId);
    return env->NewStringUTF(sdp.c_str());
}

extern "C" JNIEXPORT jint JNICALL Java_com_voicechat_client_native_WebrtcNative_applyRemoteDescription(JNIEnv* env, jobject thiz, jint peerId, jstring sdp_, jstring type_) {
    const char* sdp = sdp_ ? env->GetStringUTFChars(sdp_, NULL) : NULL;
    const char* type = type_ ? env->GetStringUTFChars(type_, NULL) : NULL;
    int res = AudioManager::instance().applyRemoteDescription((int)peerId, sdp ? std::string(sdp) : std::string(), type ? std::string(type) : std::string());
    if (sdp) env->ReleaseStringUTFChars(sdp_, sdp);
    if (type) env->ReleaseStringUTFChars(type_, type);
    return res;
}

extern "C" JNIEXPORT jint JNICALL Java_com_voicechat_client_native_WebrtcNative_addIceCandidate(JNIEnv* env, jobject thiz, jint peerId, jstring candidate_, jstring sdpMid_, jint sdpMLineIndex) {
    const char* candidate = candidate_ ? env->GetStringUTFChars(candidate_, NULL) : NULL;
    const char* sdpMid = sdpMid_ ? env->GetStringUTFChars(sdpMid_, NULL) : NULL;
    int res = AudioManager::instance().addIceCandidate((int)peerId, candidate ? std::string(candidate) : std::string(), sdpMid ? std::string(sdpMid) : std::string(), (int)sdpMLineIndex);
    if (candidate) env->ReleaseStringUTFChars(candidate_, candidate);
    if (sdpMid) env->ReleaseStringUTFChars(sdpMid_, sdpMid);
    return res;
}

extern "C" JNIEXPORT jint JNICALL Java_com_voicechat_client_native_WebrtcNative_closePeerConnection(JNIEnv* env, jobject thiz, jint peerId) {
    return AudioManager::instance().closePeerConnection((int)peerId);
}
