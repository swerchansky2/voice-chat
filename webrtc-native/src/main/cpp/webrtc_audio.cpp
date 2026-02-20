#include "webrtc_audio.h"
#include <iostream>
#include <future>
#include <memory>
#include <chrono>
#include <cstring>
#include <type_traits>
#include <utility>
#include <jni.h>

// JVM pointer provided by JNI_OnLoad (defined in webrtc_native_jni.cpp)
extern JavaVM* gJvm;

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

// AudioTrackSink that forwards decoded audio frames to Java via JNI
class JavaAudioSink : public libwebrtc::AudioTrackSink {
public:
    JavaAudioSink(int peerId) : peerId_(peerId) {}

    void OnData(const void* audio_data, int bits_per_sample, int sample_rate,
                size_t number_of_channels, size_t number_of_frames) override {
        if (!gJvm) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        int getEnvStat = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (getEnvStat == JNI_EDETACHED) {
            if (gJvm->AttachCurrentThread((void**)&env, nullptr) != 0) return;
            attached = true;
        } else if (getEnvStat == JNI_EVERSION || env == nullptr) {
            return;
        }

        jclass cls = env->FindClass("com/voicechat/client/native/WebrtcNative");
        if (!cls) goto done;
        {
            jmethodID mid = env->GetStaticMethodID(cls, "onRemoteAudioFrame", "(I[B)V");
            if (!mid) goto done;

            const size_t bytes = number_of_frames * number_of_channels * (bits_per_sample / 8);
            jbyteArray arr = env->NewByteArray((jsize)bytes);
            if (arr) {
                env->SetByteArrayRegion(arr, 0, (jsize)bytes, reinterpret_cast<const jbyte*>(audio_data));
                env->CallStaticVoidMethod(cls, mid, (jint)peerId_, arr);
                env->DeleteLocalRef(arr);
            }
        }
    done:
        if (cls) env->DeleteLocalRef(cls);
        if (attached) gJvm->DetachCurrentThread();
    }

private:
    int peerId_;
};

using libwebrtc::scoped_refptr;

class PeerObserver : public libwebrtc::RTCPeerConnectionObserver {
public:
    PeerObserver(std::shared_ptr<AudioManager::Peer> peer) : peer_(peer) {}

    void OnAddStream(scoped_refptr<libwebrtc::RTCMediaStream> stream) override {
        auto audio_tracks = stream->audio_tracks();
        for (size_t i = 0; i < audio_tracks.size(); ++i) {
            auto& track = audio_tracks[i];
            auto sink = std::make_unique<JavaAudioSink>(peer_ ? peer_->id : 0);
            track->AddSink(sink.get());
            if (peer_) {
                peer_->audioSinks.push_back(std::move(sink));
            }
        }
    }

    void OnRemoveStream(scoped_refptr<libwebrtc::RTCMediaStream> stream) override {}
    void OnDataChannel(scoped_refptr<libwebrtc::RTCDataChannel> data_channel) override {}
    void OnRenegotiationNeeded() override {}
    void OnPeerConnectionState(libwebrtc::RTCPeerConnectionState state) override {
        std::cout << "PeerObserver: connection state changed to " << (int)state
                  << " for peer " << (peer_ ? peer_->id : -1) << std::endl;
    }
    void OnIceGatheringState(libwebrtc::RTCIceGatheringState state) override {}
    void OnIceConnectionState(libwebrtc::RTCIceConnectionState state) override {}
    void OnSignalingState(libwebrtc::RTCSignalingState state) override {}
    void OnTrack(scoped_refptr<libwebrtc::RTCRtpTransceiver> transceiver) override {}
    void OnAddTrack(libwebrtc::vector<scoped_refptr<libwebrtc::RTCMediaStream>> streams, scoped_refptr<libwebrtc::RTCRtpReceiver> receiver) override {}
    void OnRemoveTrack(scoped_refptr<libwebrtc::RTCRtpReceiver> receiver) override {}

    void OnIceCandidate(scoped_refptr<libwebrtc::RTCIceCandidate> candidate) override {
        if (!gJvm || !peer_ || !candidate) return;

        libwebrtc::string candidateOut;
        candidate->ToString(candidateOut);
        std::string candidateStr = candidateOut.std_string();
        std::string sdpMid = candidate->sdp_mid().std_string();
        int sdpMLineIndex = candidate->sdp_mline_index();

        JNIEnv* env = nullptr;
        bool attached = false;
        int stat = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (stat == JNI_EDETACHED) {
            if (gJvm->AttachCurrentThread((void**)&env, nullptr) != 0) return;
            attached = true;
        } else if (stat != JNI_OK || env == nullptr) {
            return;
        }

        jclass cls = env->FindClass("com/voicechat/client/native/WebrtcNative");
        if (!cls) goto done;
        {
            jmethodID mid = env->GetStaticMethodID(cls, "onIceCandidate",
                "(ILjava/lang/String;Ljava/lang/String;I)V");
            if (!mid) goto done;

            jstring jCandidate = env->NewStringUTF(candidateStr.c_str());
            jstring jSdpMid = env->NewStringUTF(sdpMid.c_str());
            env->CallStaticVoidMethod(cls, mid, (jint)peer_->id, jCandidate, jSdpMid, (jint)sdpMLineIndex);
            env->DeleteLocalRef(jCandidate);
            env->DeleteLocalRef(jSdpMid);
        }
    done:
        if (cls) env->DeleteLocalRef(cls);
        if (attached) gJvm->DetachCurrentThread();
    }

private:
    std::shared_ptr<AudioManager::Peer> peer_;
};

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
    peer->id = id;
    // attach observer to handle remote streams/tracks
    peer->observer = std::make_unique<PeerObserver>(peer);
    peer->pc->RegisterRTCPeerConnectionObserver(peer->observer.get());
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
    peer->pc->CreateOffer(
        [prom, pc = peer->pc](auto sdp, auto type) mutable {
            std::string sdpStr = to_std_string(sdp);
            std::string typeStr = to_std_string(type);
            pc->SetLocalDescription(sdpStr, typeStr,
                []() {},
                [](const char* err) {
                    std::cerr << "AudioManager: SetLocalDescription(offer) failed: " << (err ? err : "") << std::endl;
                }
            );
            prom->set_value(sdpStr);
        },
        [prom](const char* err) {
            std::cerr << "AudioManager: CreateOffer failed: " << (err ? err : "") << std::endl;
            prom->set_value(std::string());
        },
        nullptr
    );
    if (fut.wait_for(10s) == std::future_status::ready) {
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
        [prom, pc = peer->pc](auto sdp, auto type) mutable {
            std::string sdpStr = to_std_string(sdp);
            std::string typeStr = to_std_string(type);
            pc->SetLocalDescription(sdpStr, typeStr,
                []() {},
                [](const char* err) {
                    std::cerr << "AudioManager: SetLocalDescription(answer) failed: " << (err ? err : "") << std::endl;
                }
            );
            prom->set_value(sdpStr);
        },
        [prom](const char* err) {
            std::cerr << "AudioManager: CreateAnswer failed: " << (err ? err : "") << std::endl;
            prom->set_value(std::string());
        },
        nullptr
    );
    if (fut.wait_for(10s) == std::future_status::ready) {
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
        std::vector<int16_t> buffer(frames * channels);
        while (*running) {
            // silence (zeros) - or generate tone here
            memset(buffer.data(), 0, buffer.size() * sizeof(int16_t));
            src->CaptureFrame(buffer.data(), 16, sample_rate, channels, frames);
            std::this_thread::sleep_for(std::chrono::milliseconds(frame_ms));
        }
    });
    return 0;
}

int AudioManager::pushAudioFrame(int peerId, const void* audio_data, int bitsPerSample, int sampleRate, int channels, int frames) {
    std::shared_ptr<Peer> peer;
    {
        std::lock_guard<std::mutex> g(mtx_);
        auto it = peers_.find(peerId);
        if (it == peers_.end()) return -1;
        peer = it->second;
    }
    if (!peer->audioSource) return -1;
    // Forward raw PCM to the RTCAudioSource
    peer->audioSource->CaptureFrame(audio_data, bitsPerSample, sampleRate, channels, frames);
    return 0;
}
