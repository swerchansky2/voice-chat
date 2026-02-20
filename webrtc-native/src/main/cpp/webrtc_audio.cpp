#include "webrtc_audio.h"
#include <iostream>
#include <future>
#include <memory>
#include <chrono>
#include <cstring>
#include <type_traits>
#include <utility>

namespace {
template<typename T, typename = void>
struct has_c_str : std::false_type {};

template<typename T>
struct has_c_str<T, std::void_t<decltype(std::declval<T>().c_str())>> : std::true_type {};

template<typename T, typename = void>
struct has_data_size : std::false_type {};

template<typename T>
struct has_data_size<T, std::void_t<decltype(std::declval<T>().data()), decltype(std::declval<T>().size())>> : std::true_type {};

template<typename T>
std::string to_std_string(const T& s) {
    if constexpr (std::is_convertible_v<T, std::string>) {
        return std::string(s);
    } else if constexpr (has_c_str<T>::value) {
        return std::string(s.c_str());
    } else if constexpr (has_data_size<T>::value) {
        return std::string(s.data(), s.size());
    } else {
        static_assert(sizeof(T) == 0, "Unsupported string-like type for to_std_string");
    }
}
} // namespace

using namespace std::chrono_literals;

AudioManager& AudioManager::instance() {
    static AudioManager inst;
    return inst;
}

AudioManager::AudioManager() {}
AudioManager::~AudioManager() {
    shutdown();
}

int AudioManager::initialize() {
    std::lock_guard<std::mutex> g(mtx_);
    if (initialized_) {
        return 0;
    }

    std::cout << "AudioManager: initializing libwebrtc via LibWebRTC" << std::endl;
    if (!libwebrtc::LibWebRTC::Initialize()) {
        std::cerr << "AudioManager: LibWebRTC::Initialize failed" << std::endl;
        return -1;
    }
    pcFactory_ = libwebrtc::LibWebRTC::CreateRTCPeerConnectionFactory();
    if (!pcFactory_) {
        std::cerr << "AudioManager: CreateRTCPeerConnectionFactory failed" << std::endl;
        libwebrtc::LibWebRTC::Terminate();
        return -1;
    }
    initialized_ = true;
    return 0;
}

void AudioManager::shutdown() {
    std::lock_guard<std::mutex> g(mtx_);
    if (!initialized_) return;
    std::cout << "AudioManager: shutting down" << std::endl;
    for (auto &p : peers_) {
        if (p.second->audioRunning) {
            p.second->audioRunning = false;
            if (p.second->audioThread.joinable()) p.second->audioThread.join();
        }
        if (p.second->pc) {
            p.second->pc->Close();
        }
    }
    peers_.clear();
    if (pcFactory_) {
        pcFactory_->Terminate();
        pcFactory_ = nullptr;
    }
    libwebrtc::LibWebRTC::Terminate();
    initialized_ = false;
}

int AudioManager::createPeerConnection() {
    std::lock_guard<std::mutex> g(mtx_);
    if (!initialized_) {
        std::cerr << "AudioManager: createPeerConnection called before initialize" << std::endl;
        return -1;
    }
    libwebrtc::RTCConfiguration config; // default
    auto peer = std::make_shared<Peer>();
    auto pc = pcFactory_->Create(config, nullptr);
    if (!pc) {
        std::cerr << "AudioManager: failed to create RTCPeerConnection" << std::endl;
        return -1;
    }
    peer->pc = pc;
    int id = nextPeerId_++;
    peers_.emplace(id, peer);
    std::cout << "AudioManager: createPeerConnection -> " << id << std::endl;
    return id;
}

std::string AudioManager::createOffer(int peerId) {
    std::shared_ptr<Peer> peer;
    {
        std::lock_guard<std::mutex> g(mtx_);
        auto it = peers_.find(peerId);
        if (it == peers_.end()) return std::string();
        peer = it->second;
    }
    auto prom = std::make_shared<std::promise<std::string>>();
    auto fut = prom->get_future();
    // vendor API: CreateOffer(OnSdpCreateSuccess, OnSdpCreateFailure, constraints)
    peer->pc->CreateOffer(
        [prom](auto sdp, auto type) mutable {
            // convert vendor portable/string-like to std::string
            prom->set_value(to_std_string(sdp));
        },
        [](const char* err) {
            std::cerr << "AudioManager: CreateOffer failed: " << (err ? err : "") << std::endl;
        },
        nullptr
    );
    if (fut.wait_for(5s) == std::future_status::ready) {
        return fut.get();
    }
    std::cerr << "AudioManager: createOffer timed out" << std::endl;
    return std::string();
}

std::string AudioManager::createAnswer(int peerId) {
    std::shared_ptr<Peer> peer;
    {
        std::lock_guard<std::mutex> g(mtx_);
        auto it = peers_.find(peerId);
        if (it == peers_.end()) return std::string();
        peer = it->second;
    }
    auto prom = std::make_shared<std::promise<std::string>>();
    auto fut = prom->get_future();
    peer->pc->CreateAnswer(
        [prom](auto sdp, auto type) mutable {
            prom->set_value(to_std_string(sdp));
        },
        [](const char* err) {
            std::cerr << "AudioManager: CreateAnswer failed: " << (err ? err : "") << std::endl;
        },
        nullptr
    );
    if (fut.wait_for(5s) == std::future_status::ready) {
        return fut.get();
    }
    std::cerr << "AudioManager: createAnswer timed out" << std::endl;
    return std::string();
}

int AudioManager::applyRemoteDescription(int peerId, const std::string& sdp, const std::string& type) {
    std::shared_ptr<Peer> peer;
    {
        std::lock_guard<std::mutex> g(mtx_);
        auto it = peers_.find(peerId);
        if (it == peers_.end()) return -1;
        peer = it->second;
    }
    auto prom = std::make_shared<std::promise<bool>>();
    auto fut = prom->get_future();
    peer->pc->SetRemoteDescription(
        sdp, type,
        [prom]() mutable { prom->set_value(true); },
        [prom](const char* err) mutable { prom->set_value(false); }
    );
    if (fut.wait_for(5s) == std::future_status::ready) {
        return fut.get() ? 0 : -1;
    }
    return -1;
}

int AudioManager::addIceCandidate(int peerId, const std::string& candidate, const std::string& sdpMid, int sdpMLineIndex) {
    std::shared_ptr<Peer> peer;
    {
        std::lock_guard<std::mutex> g(mtx_);
        auto it = peers_.find(peerId);
        if (it == peers_.end()) return -1;
        peer = it->second;
    }
    peer->pc->AddCandidate(sdpMid, sdpMLineIndex, candidate);
    return 0;
}

int AudioManager::closePeerConnection(int peerId) {
    std::lock_guard<std::mutex> g(mtx_);
    auto it = peers_.find(peerId);
    if (it == peers_.end()) return -1;
    auto peer = it->second;
    if (peer->audioRunning) {
        peer->audioRunning = false;
        if (peer->audioThread.joinable()) peer->audioThread.join();
    }
    if (peer->pc) peer->pc->Close();
    peers_.erase(it);
    return 0;
}

int AudioManager::addLocalAudioTrack(int peerId, const std::string& trackId) {
    std::shared_ptr<Peer> peer;
    {
        std::lock_guard<std::mutex> g(mtx_);
        auto it = peers_.find(peerId);
        if (it == peers_.end()) return -1;
        peer = it->second;
    }
    // Create a custom audio source and attach to the peer connection as a track
    auto source = pcFactory_->CreateAudioSource("local_audio", libwebrtc::RTCAudioSource::SourceType::kCustom);
    if (!source) return -1;
    auto track = pcFactory_->CreateAudioTrack(source, trackId);
    if (!track) return -1;
    peer->audioSource = source;
    peer->audioTrack = track;
    peer->pc->AddTrack(track, std::vector<std::string>{"audio"});
    return 0;
}

int AudioManager::startAudioGenerator(int peerId) {
    std::shared_ptr<Peer> peer;
    {
        std::lock_guard<std::mutex> g(mtx_);
        auto it = peers_.find(peerId);
        if (it == peers_.end()) return -1;
        peer = it->second;
    }
    if (!peer->audioSource) return -1;
    if (peer->audioRunning) return 0;
    peer->audioRunning = true;
    // Simple generator: push silence frames periodically (48kHz mono 16-bit)
    peer->audioThread = std::thread([src = peer->audioSource, running = &peer->audioRunning]() {
        const int sample_rate = 48000;
        const int channels = 1;
        const int frame_ms = 10;
        const int frames = sample_rate * frame_ms / 1000;
        std::vector<int16_t> buffer(frames * channels, 0);
        while (*running) {
            // silence (zeros) - or generate tone here
            //memset(buffer.data(), 0, buffer.size() * sizeof(int16_t));
            src->CaptureFrame(buffer.data(), 16, sample_rate, channels, frames);
            std::this_thread::sleep_for(std::chrono::milliseconds(frame_ms));
        }
    });
    return 0;
}
