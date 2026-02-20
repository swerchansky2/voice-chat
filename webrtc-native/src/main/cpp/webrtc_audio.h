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

class AudioManager {
public:
    static AudioManager& instance();

    // Initialize libwebrtc runtime and factory. Returns 0 on success.
    int initialize();
    void shutdown();

    // Peer connection lifecycle
    int createPeerConnection();
    std::string createOffer(int peerId);
    std::string createAnswer(int peerId);
    int applyRemoteDescription(int peerId, const std::string& sdp, const std::string& type);
    int addIceCandidate(int peerId, const std::string& candidate, const std::string& sdpMid, int sdpMLineIndex);
    int closePeerConnection(int peerId);

    // Audio utilities
    int addLocalAudioTrack(int peerId, const std::string& trackId);
    int startAudioGenerator(int peerId); // generates synthetic audio into the RTCAudioSource

private:
    AudioManager();
    ~AudioManager();

    struct Peer {
        libwebrtc::scoped_refptr<libwebrtc::RTCPeerConnection> pc;
        libwebrtc::scoped_refptr<libwebrtc::RTCAudioSource> audioSource;
        libwebrtc::scoped_refptr<libwebrtc::RTCAudioTrack> audioTrack;
        std::thread audioThread;
        bool audioRunning = false;
    };

    std::mutex mtx_;
    int nextPeerId_ = 1;
    std::unordered_map<int, std::shared_ptr<Peer>> peers_;

    bool initialized_ = false;
    libwebrtc::scoped_refptr<libwebrtc::RTCPeerConnectionFactory> pcFactory_;
};
