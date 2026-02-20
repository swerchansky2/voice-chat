#pragma once

#include <string>
#include <unordered_map>
#include <memory>
#include <mutex>
#include <thread>

// Vendored libwebrtc wrapper headers
#include "libwebrtc.h"
#include "rtc_peerconnection_factory.h"
#include "rtc_peerconnection.h"
#include "rtc_session_description.h"
#include "rtc_ice_candidate.h"

class JavaAudioSink;

class AudioManager {
public:
    static AudioManager& instance();

    int initialize();
    void shutdown();

    int createPeerConnection();
    std::string createOffer(int peerId);
    std::string createAnswer(int peerId);
    int applyRemoteDescription(int peerId, const std::string& sdp, const std::string& type);
    int addIceCandidate(int peerId, const std::string& candidate, const std::string& sdpMid, int sdpMLineIndex);
    int closePeerConnection(int peerId);

    int addLocalAudioTrack(int peerId, const std::string& trackId);
    int startAudioGenerator(int peerId);
    int pushAudioFrame(int peerId, const void* audio_data, int bitsPerSample, int sampleRate, int channels, int frames);

    struct Peer {
        int id = -1;
        libwebrtc::scoped_refptr<libwebrtc::RTCPeerConnection> pc;
        libwebrtc::scoped_refptr<libwebrtc::RTCAudioSource> audioSource;
        libwebrtc::scoped_refptr<libwebrtc::RTCAudioTrack> audioTrack;
        std::vector<std::unique_ptr<JavaAudioSink>> audioSinks;
        std::unique_ptr<libwebrtc::RTCPeerConnectionObserver> observer;
        std::thread audioThread;
        bool audioRunning = false;
    };

private:
    AudioManager();
    ~AudioManager();

    std::mutex mtx_;
    int nextPeerId_ = 1;
    std::unordered_map<int, std::shared_ptr<Peer>> peers_;

    bool initialized_ = false;
    libwebrtc::scoped_refptr<libwebrtc::RTCPeerConnectionFactory> pcFactory_;
};
