#pragma once
// Continuous OpenXR frame loop with ONE projection layer per frame and a per-frame head locate.
// Standard OpenXR ABI only (resolved through xrGetInstanceProcAddr). Time is the runtime's
// predictedDisplayTime, kept as raw XrTime; no conversion or generated time.
// Runtime behaviour this loop is built around (RayNeo X3 Pro, see docs/x3-pro-runtime-notes.md):
//   * xrEndFrame with zero layers crashes the runtime -> every begun frame ends with one projection layer;
//   * a second xrWaitFrame without xrEndFrame returns -37 -> every begun frame is ended.
// Rendering uses VIEW-space eye poses from xrLocateViews, so the submitted layer does not depend on
// head-tracking state. Tracking comes only from xrLocateSpace(VIEW, LOCAL, displayTime).
// Optional JSONL journal through FrameLoopIo::emit (debugging); the SDK leaves it unset.
#include "xr_common.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <map>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

namespace xrspatial {
#ifdef XR_USE_GRAPHICS_API_OPENGL_ES
using SwapchainGlesImage = XrSwapchainImageOpenGLESKHR;
#else
// HOST ONLY mirror of the published GLES image struct (platform header needs EGL types).
struct SwapchainGlesImage { XrStructureType type; void* next; uint32_t image; };
#endif

struct FrameLoopLimits {
    int64_t durationMs = 20000;
    uint32_t maxFrames = 20000;
    // Every frame is journaled while index < fullJournalFrames; afterwards only frames whose pose,
    // flags, display-time step or a result changed, plus every sampleEvery-th frame (cadence sample).
    uint32_t fullJournalFrames = 300;
    uint32_t sampleEvery = 10;
    int64_t readyTimeoutMs = 5000;
    int64_t stopTimeoutMs = 3000;
    uint32_t width = 640, height = 480;
};

// One successful head locate, raw as returned (values are whatever the runtime wrote, even when the
// corresponding valid bit is clear). hostReceiptNs is taken right after xrLocateSpace returned.
struct LocatedPose {
    uint32_t index = 0;
    int64_t xrTime = 0, hostReceiptNs = 0;
    uint64_t flags = 0;
    XrPosef pose{};
};

struct FrameLoopIo {
    std::function<bool(const std::string& line, bool durable)> emit;
    std::function<bool()> cancelled;
    std::function<int64_t()> monotonicNs;  // host diagnostic marker, NOT XrTime
    std::function<void()> idle;            // short sleep while waiting for session events
    // Draw one eye into the acquired GL texture. trackingFlags are this frame's raw head flags.
    std::function<bool(unsigned eye, uint32_t image, uint32_t width, uint32_t height, uint64_t trackingFlags)> drawEye;
    // Optional per-frame delivery of every successful head locate (SDK use); called on the loop thread.
    std::function<void(const LocatedPose&)> sample;
    // Optional, called once per begun frame before the eyes are drawn (world-locked scene): whether the
    // head locate returned XR_SUCCESS, the raw head location, and the eye views used for the layer.
    std::function<void(bool located, const XrSpaceLocation& head, const std::array<XrView, 2>& views)> frameViews;
};

template <class T> T xrStruct(XrStructureType type) {
    T value{};
    value.type = type;
    return value;
}

inline std::string jsonFloat(float v) {
    if (!std::isfinite(v)) return "null";
    char text[32];
    std::snprintf(text, sizeof text, "%.9g", static_cast<double>(v));
    return text;
}
inline std::string jsonBits(float v) {
    uint32_t bits;
    std::memcpy(&bits, &v, sizeof bits);
    char text[16];
    std::snprintf(text, sizeof text, "\"%08x\"", bits);
    return text;
}

struct FrameLoopSummary {
    std::string status = "NOT_STARTED", failure, endReason;
    uint32_t framesWaited = 0, framesBegun = 0, framesEnded = 0, locatesReturned = 0, allFourFlags = 0;
    uint32_t viewFallbackFrames = 0, events = 0, framesJournaled = 0, poseChanges = 0;
    bool sessionBegun = false, exitRequested = false, stoppingObserved = false, sessionEnded = false;
    bool exitingObserved = false;  // sticky: a later state must not hide EXITING/LOSS_PENDING
    bool timeStrictlyIncreasing = true, cleanupOk = true;
    int32_t lastState = 0;
    int64_t swapchainFormat = 0;
    std::optional<int64_t> firstDisplayTime, lastDisplayTime, minDelta, maxDelta, firstPeriod;
    std::map<uint64_t, uint32_t> flagCounts;
    std::vector<Operation> setupOperations;
    std::string toJson() const {
        auto b = [](bool v) { return v ? "true" : "false"; };
        auto raw = [](const std::optional<int64_t>& v) { return v ? jsonQuote(std::to_string(*v)) : std::string("null"); };
        std::ostringstream o;
        o << "{\"schemaVersion\":1,\"stage\":\"FRAME_LOOP\",\"status\":" << jsonQuote(status)
          << ",\"failure\":" << jsonQuote(failure) << ",\"endReason\":" << jsonQuote(endReason)
          << ",\"framesWaited\":" << framesWaited << ",\"framesBegun\":" << framesBegun << ",\"framesEnded\":" << framesEnded
          << ",\"locatesReturned\":" << locatesReturned << ",\"allFourFlagsFrames\":" << allFourFlags
          << ",\"viewFallbackFrames\":" << viewFallbackFrames << ",\"events\":" << events
          << ",\"framesJournaled\":" << framesJournaled << ",\"poseChanges\":" << poseChanges
          << ",\"exitingObserved\":" << b(exitingObserved)
          << ",\"sessionBegun\":" << b(sessionBegun) << ",\"exitRequested\":" << b(exitRequested)
          << ",\"stoppingObserved\":" << b(stoppingObserved) << ",\"sessionEnded\":" << b(sessionEnded)
          << ",\"lastState\":" << lastState << ",\"swapchainFormat\":" << swapchainFormat
          << ",\"timeStrictlyIncreasing\":" << b(timeStrictlyIncreasing)
          << ",\"firstDisplayTime\":" << raw(firstDisplayTime) << ",\"lastDisplayTime\":" << raw(lastDisplayTime)
          << ",\"firstPeriod\":" << raw(firstPeriod) << ",\"minDisplayTimeDelta\":" << raw(minDelta)
          << ",\"maxDisplayTimeDelta\":" << raw(maxDelta) << ",\"cleanupOk\":" << b(cleanupOk)
          << ",\"timeDomain\":\"XR_TIME_RAW_NOT_CONVERTED\",\"flagCounts\":{";
        bool first = true;
        for (const auto& [flags, count] : flagCounts) {
            if (!first) o << ',';
            first = false;
            o << jsonQuote(std::to_string(flags)) << ':' << count;
        }
        o << "},\"setupOperations\":[";
        for (size_t i = 0; i < setupOperations.size(); ++i) {
            if (i) o << ',';
            const auto& op = setupOperations[i];
            o << "{\"operation\":" << jsonQuote(op.name) << ",\"result\":" << int(op.result) << '}';
        }
        o << "]}";
        return o.str();
    }
};

class FrameLoop {
    struct Stop { std::string reason; };
    PFN_xrGetInstanceProcAddr getProc;
    XrInstance instance;
    XrSession session;
    XrEnvironmentBlendMode blend;
    FrameLoopLimits limits;
    FrameLoopIo io;
    FrameLoopSummary summary;

    PFN_xrPollEvent pollEvent = nullptr;
    PFN_xrBeginSession beginSession = nullptr;
    PFN_xrEndSession endSession = nullptr;
    PFN_xrRequestExitSession requestExit = nullptr;
    PFN_xrEnumerateReferenceSpaces enumerateSpaces = nullptr;
    PFN_xrCreateReferenceSpace createSpace = nullptr;
    PFN_xrDestroySpace destroySpace = nullptr;
    PFN_xrEnumerateSwapchainFormats enumerateFormats = nullptr;
    PFN_xrCreateSwapchain createSwapchain = nullptr;
    PFN_xrDestroySwapchain destroySwapchain = nullptr;
    PFN_xrEnumerateSwapchainImages enumerateImages = nullptr;
    PFN_xrAcquireSwapchainImage acquireImage = nullptr;
    PFN_xrWaitSwapchainImage waitImage = nullptr;
    PFN_xrReleaseSwapchainImage releaseImage = nullptr;
    PFN_xrWaitFrame waitFrame = nullptr;
    PFN_xrBeginFrame beginFrame = nullptr;
    PFN_xrEndFrame endFrame = nullptr;
    PFN_xrLocateViews locateViews = nullptr;
    PFN_xrLocateSpace locateSpace = nullptr;

    XrSpace localSpace = XR_NULL_HANDLE, viewSpace = XR_NULL_HANDLE;
    std::array<XrSwapchain, 2> swapchains{};
    std::array<std::vector<SwapchainGlesImage>, 2> images;
    std::array<XrView, 2> lastViews{};
    bool haveViews = false;
    int64_t startNs = 0;
    std::string lastPose, lastFlags, lastStep;

    void emit(const std::string& line, bool durable) {
        if (io.emit && !io.emit(line, durable)) throw Stop{"JournalWriteFailed"};
    }
    int64_t now() const { return io.monotonicNs ? io.monotonicNs() : 0; }
    int64_t elapsedMs() const { return (now() - startNs) / 1000000; }
    bool cancelled() const { return io.cancelled && io.cancelled(); }
    XrResult setup(const std::string& name, XrResult result) {
        summary.setupOperations.push_back({name, result, resultName(result)});
        emit("{\"t\":\"op\",\"name\":" + jsonQuote(name) + ",\"result\":" + std::to_string(int(result)) +
             ",\"hostNs\":" + std::to_string(now()) + "}", true);
        return result;
    }
    void require(const std::string& name, XrResult result) {
        if (setup(name, result) != XR_SUCCESS) throw Stop{name};
    }
    template <class T> T proc(const char* name) {
        PFN_xrVoidFunction fn = nullptr;
        require(std::string("xrGetInstanceProcAddr(") + name + ")", getProc(instance, name, &fn));
        if (!fn) throw Stop{std::string("NullFunction:") + name};
        return reinterpret_cast<T>(fn);
    }

    void handleEvent(const XrEventDataBuffer& event) {
        ++summary.events;
        std::ostringstream o;
        o << "{\"t\":\"event\",\"type\":" << int(event.type) << ",\"hostNs\":" << now();
        if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            const auto& e = *reinterpret_cast<const XrEventDataSessionStateChanged*>(&event);
            const bool own = e.session == session;
            o << ",\"state\":" << int(e.state) << ",\"xrTime\":" << jsonQuote(std::to_string(e.time))
              << ",\"ownSession\":" << (own ? "true" : "false");
            if (own) {
                summary.lastState = int32_t(e.state);
                if (e.state == XR_SESSION_STATE_STOPPING) summary.stoppingObserved = true;
                if (e.state == XR_SESSION_STATE_EXITING || e.state == XR_SESSION_STATE_LOSS_PENDING) summary.exitingObserved = true;
            }
        } else if (event.type == XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING) {
            const auto& e = *reinterpret_cast<const XrEventDataReferenceSpaceChangePending*>(&event);
            o << ",\"referenceSpaceType\":" << int(e.referenceSpaceType) << ",\"changeTime\":"
              << jsonQuote(std::to_string(e.changeTime)) << ",\"poseValid\":" << e.poseValid;
        } else if (event.type == XR_TYPE_EVENT_DATA_EVENTS_LOST) {
            o << ",\"lostEventCount\":" << reinterpret_cast<const XrEventDataEventsLost*>(&event)->lostEventCount;
        }
        o << '}';
        emit(o.str(), true);
    }
    void pollAll() {
        for (unsigned i = 0; i < 16; ++i) {
            XrEventDataBuffer event{};
            event.type = XR_TYPE_EVENT_DATA_BUFFER;
            const auto r = pollEvent(instance, &event);
            if (r == XR_EVENT_UNAVAILABLE) return;
            if (r != XR_SUCCESS) { setup("xrPollEvent", r); throw Stop{"xrPollEvent"}; }
            handleEvent(event);
            if (event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) throw Stop{"InstanceLossPending"};
        }
    }
    bool lost() const {
        return summary.exitingObserved;
    }

    void prepare() {
        pollEvent = proc<PFN_xrPollEvent>("xrPollEvent");
        beginSession = proc<PFN_xrBeginSession>("xrBeginSession");
        endSession = proc<PFN_xrEndSession>("xrEndSession");
        requestExit = proc<PFN_xrRequestExitSession>("xrRequestExitSession");
        enumerateSpaces = proc<PFN_xrEnumerateReferenceSpaces>("xrEnumerateReferenceSpaces");
        createSpace = proc<PFN_xrCreateReferenceSpace>("xrCreateReferenceSpace");
        destroySpace = proc<PFN_xrDestroySpace>("xrDestroySpace");
        enumerateFormats = proc<PFN_xrEnumerateSwapchainFormats>("xrEnumerateSwapchainFormats");
        createSwapchain = proc<PFN_xrCreateSwapchain>("xrCreateSwapchain");
        destroySwapchain = proc<PFN_xrDestroySwapchain>("xrDestroySwapchain");
        enumerateImages = proc<PFN_xrEnumerateSwapchainImages>("xrEnumerateSwapchainImages");
        acquireImage = proc<PFN_xrAcquireSwapchainImage>("xrAcquireSwapchainImage");
        waitImage = proc<PFN_xrWaitSwapchainImage>("xrWaitSwapchainImage");
        releaseImage = proc<PFN_xrReleaseSwapchainImage>("xrReleaseSwapchainImage");
        waitFrame = proc<PFN_xrWaitFrame>("xrWaitFrame");
        beginFrame = proc<PFN_xrBeginFrame>("xrBeginFrame");
        endFrame = proc<PFN_xrEndFrame>("xrEndFrame");
        locateViews = proc<PFN_xrLocateViews>("xrLocateViews");
        locateSpace = proc<PFN_xrLocateSpace>("xrLocateSpace");

        // The runtime announces READY before BeginSession is legal.
        while (summary.lastState != XR_SESSION_STATE_READY) {
            if (cancelled()) throw Stop{"CancelledBeforeReady"};
            if (elapsedMs() > limits.readyTimeoutMs) throw Stop{"ReadyTimeout"};
            pollAll();
            if (lost() || summary.stoppingObserved) throw Stop{"SessionEndedBeforeReady"};
            if (summary.lastState != XR_SESSION_STATE_READY && io.idle) io.idle();
        }

        uint32_t count = 0;
        require("xrEnumerateReferenceSpaces(count)", enumerateSpaces(session, 0, &count, nullptr));
        if (count == 0 || count > 32) throw Stop{"ReferenceSpaceCount"};
        std::vector<XrReferenceSpaceType> types(count);
        require("xrEnumerateReferenceSpaces(data)", enumerateSpaces(session, count, &count, types.data()));
        if (count > types.size()) throw Stop{"ReferenceSpaceCountChanged"};
        for (auto wanted : {XR_REFERENCE_SPACE_TYPE_LOCAL, XR_REFERENCE_SPACE_TYPE_VIEW})
            if (std::find(types.begin(), types.begin() + count, wanted) == types.begin() + count) throw Stop{"RequiredReferenceSpaceMissing"};
        for (auto type : {XR_REFERENCE_SPACE_TYPE_LOCAL, XR_REFERENCE_SPACE_TYPE_VIEW}) {
            auto info = xrStruct<XrReferenceSpaceCreateInfo>(XR_TYPE_REFERENCE_SPACE_CREATE_INFO);
            info.referenceSpaceType = type;
            info.poseInReferenceSpace.orientation.w = 1;
            XrSpace& out = type == XR_REFERENCE_SPACE_TYPE_LOCAL ? localSpace : viewSpace;
            const auto r = createSpace(session, &info, &out);
            if (r != XR_SUCCESS) out = XR_NULL_HANDLE;
            require(type == XR_REFERENCE_SPACE_TYPE_LOCAL ? "xrCreateReferenceSpace(LOCAL)" : "xrCreateReferenceSpace(VIEW)", r);
            if (out == XR_NULL_HANDLE) throw Stop{"NullSpace"};
        }

        uint32_t formatCount = 0;
        require("xrEnumerateSwapchainFormats(count)", enumerateFormats(session, 0, &formatCount, nullptr));
        if (formatCount == 0 || formatCount > 64) throw Stop{"SwapchainFormatCount"};
        std::vector<int64_t> formats(formatCount);
        require("xrEnumerateSwapchainFormats(data)", enumerateFormats(session, formatCount, &formatCount, formats.data()));
        formats.resize(formatCount);
        // Previously observed format 35907 (GL_SRGB8_ALPHA8); GL_RGBA8 accepted if that is what is offered.
        for (int64_t candidate : {int64_t(35907), int64_t(32856)})
            if (std::find(formats.begin(), formats.end(), candidate) != formats.end()) { summary.swapchainFormat = candidate; break; }
        if (!summary.swapchainFormat) throw Stop{"NoSupportedColorFormat"};

        for (unsigned eye = 0; eye < 2; ++eye) {
            auto info = xrStruct<XrSwapchainCreateInfo>(XR_TYPE_SWAPCHAIN_CREATE_INFO);
            info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
            info.format = summary.swapchainFormat;
            info.sampleCount = 1;  // views report 0; 1 was accepted by this runtime before
            info.width = limits.width;
            info.height = limits.height;
            info.faceCount = info.arraySize = info.mipCount = 1;
            const auto r = createSwapchain(session, &info, &swapchains[eye]);
            if (r != XR_SUCCESS) swapchains[eye] = XR_NULL_HANDLE;
            require(eye ? "xrCreateSwapchain(right)" : "xrCreateSwapchain(left)", r);
            if (swapchains[eye] == XR_NULL_HANDLE) throw Stop{"NullSwapchain"};
            uint32_t imageCount = 0;
            require("xrEnumerateSwapchainImages(count)", enumerateImages(swapchains[eye], 0, &imageCount, nullptr));
            if (imageCount == 0 || imageCount > 16) throw Stop{"SwapchainImageCount"};
            images[eye].assign(imageCount, SwapchainGlesImage{XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR, nullptr, 0});
            require("xrEnumerateSwapchainImages(data)", enumerateImages(swapchains[eye], imageCount, &imageCount,
                reinterpret_cast<XrSwapchainImageBaseHeader*>(images[eye].data())));
            if (imageCount != images[eye].size()) throw Stop{"SwapchainImageCountChanged"};
        }

        auto begin = xrStruct<XrSessionBeginInfo>(XR_TYPE_SESSION_BEGIN_INFO);
        begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        require("xrBeginSession", beginSession(session, &begin));
        summary.sessionBegun = true;
    }

    // Eye poses for the layer. Render-only: never written to the tracking record.
    void layerViews(std::array<XrView, 2>& views, uint32_t viewFlags, XrResult r, uint32_t viewCount, std::string& source) {
        const XrViewStateFlags needed = XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT;
        if (r == XR_SUCCESS && viewCount == 2 && (viewFlags & needed) == needed) {
            lastViews = views; haveViews = true; source = "LOCATE_VIEWS"; return;
        }
        ++summary.viewFallbackFrames;
        if (haveViews) { views = lastViews; source = "PREVIOUS_LOCATE_VIEWS"; return; }
        for (unsigned eye = 0; eye < 2; ++eye) {
            views[eye] = xrStruct<XrView>(XR_TYPE_VIEW);
            views[eye].pose.orientation.w = 1;
            views[eye].pose.position.x = eye ? 0.032f : -0.032f;
            views[eye].fov = XrFovf{-0.35f, 0.35f, 0.27f, -0.27f};
        }
        source = "DEFAULT_RENDER_ONLY";
    }

    // Returns false when the loop must stop (failure is recorded in summary).
    bool frame(uint32_t index) {
        std::ostringstream o;
        o << "{\"t\":\"frame\",\"i\":" << index << ",\"hostNs\":" << now();
        auto waitInfo = xrStruct<XrFrameWaitInfo>(XR_TYPE_FRAME_WAIT_INFO);
        auto state = xrStruct<XrFrameState>(XR_TYPE_FRAME_STATE);
        const auto waited = waitFrame(session, &waitInfo, &state);
        o << ",\"wait\":" << int(waited);
        if (waited != XR_SUCCESS) { emit(o.str() + "}", true); summary.failure = "xrWaitFrame"; return false; }
        ++summary.framesWaited;
        const int64_t time = state.predictedDisplayTime;
        o << ",\"pdt\":" << jsonQuote(std::to_string(time)) << ",\"period\":"
          << jsonQuote(std::to_string(state.predictedDisplayPeriod)) << ",\"shouldRender\":" << state.shouldRender;
        const std::string stepText = summary.lastDisplayTime ? std::to_string(time - *summary.lastDisplayTime) : std::string();
        if (summary.lastDisplayTime) {
            const int64_t delta = time - *summary.lastDisplayTime;
            if (delta <= 0) summary.timeStrictlyIncreasing = false;
            summary.minDelta = summary.minDelta ? std::min(*summary.minDelta, delta) : delta;
            summary.maxDelta = summary.maxDelta ? std::max(*summary.maxDelta, delta) : delta;
        } else {
            summary.firstDisplayTime = time;
            summary.firstPeriod = state.predictedDisplayPeriod;
        }
        summary.lastDisplayTime = time;

        auto beginInfo = xrStruct<XrFrameBeginInfo>(XR_TYPE_FRAME_BEGIN_INFO);
        const auto begun = beginFrame(session, &beginInfo);
        o << ",\"begin\":" << int(begun);
        if (begun != XR_SUCCESS && begun != XR_FRAME_DISCARDED) { emit(o.str() + "}", true); summary.failure = "xrBeginFrame"; return false; }
        ++summary.framesBegun;

        // Head pose relative to LOCAL at the frame's display time: the tracking record.
        auto head = xrStruct<XrSpaceLocation>(XR_TYPE_SPACE_LOCATION);
        const auto located = locateSpace(viewSpace, localSpace, time, &head);
        const int64_t receiptNs = now();
        o << ",\"locate\":" << int(located);
        uint64_t flags = 0;
        std::string pose;
        if (located == XR_SUCCESS) {
            ++summary.locatesReturned;
            flags = head.locationFlags;
            ++summary.flagCounts[flags];
            if ((flags & 15) == 15) ++summary.allFourFlags;
            o << ",\"flags\":" << jsonQuote(std::to_string(flags));
            if (flags & XR_SPACE_LOCATION_POSITION_VALID_BIT) {
                const auto& p = head.pose.position;
                o << ",\"p\":[" << jsonFloat(p.x) << ',' << jsonFloat(p.y) << ',' << jsonFloat(p.z)
                  << "],\"pb\":[" << jsonBits(p.x) << ',' << jsonBits(p.y) << ',' << jsonBits(p.z) << ']';
                pose += jsonBits(p.x) + jsonBits(p.y) + jsonBits(p.z);
            }
            if (flags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) {
                const auto& q = head.pose.orientation;
                o << ",\"q\":[" << jsonFloat(q.x) << ',' << jsonFloat(q.y) << ',' << jsonFloat(q.z) << ','
                  << jsonFloat(q.w) << "],\"qb\":[" << jsonBits(q.x) << ',' << jsonBits(q.y) << ','
                  << jsonBits(q.z) << ',' << jsonBits(q.w) << ']';
                pose += jsonBits(q.x) + jsonBits(q.y) + jsonBits(q.z) + jsonBits(q.w);
            }
            if (io.sample) io.sample({index, time, receiptNs, flags, head.pose});
        }

        auto viewInfo = xrStruct<XrViewLocateInfo>(XR_TYPE_VIEW_LOCATE_INFO);
        viewInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        viewInfo.displayTime = time;
        viewInfo.space = viewSpace;
        auto viewState = xrStruct<XrViewState>(XR_TYPE_VIEW_STATE);
        std::array<XrView, 2> views{xrStruct<XrView>(XR_TYPE_VIEW), xrStruct<XrView>(XR_TYPE_VIEW)};
        uint32_t viewCount = 0;
        const auto viewed = locateViews(session, &viewInfo, &viewState, 2, &viewCount, views.data());
        std::string source;
        layerViews(views, uint32_t(viewState.viewStateFlags), viewed, viewCount, source);
        o << ",\"views\":" << int(viewed) << ",\"viewCount\":" << viewCount << ",\"viewFlags\":" << viewState.viewStateFlags
          << ",\"layerViews\":\"" << source << '"';
        if (index < 3) {
            // Raw eye geometry of the first frames (render input of the world-locked scene).
            o << ",\"eyeViews\":[";
            for (unsigned eye = 0; eye < 2; ++eye) {
                const auto& v = views[eye];
                o << (eye ? "," : "") << "{\"position\":[" << jsonFloat(v.pose.position.x) << ',' << jsonFloat(v.pose.position.y) << ','
                  << jsonFloat(v.pose.position.z) << "],\"orientation\":[" << jsonFloat(v.pose.orientation.x) << ','
                  << jsonFloat(v.pose.orientation.y) << ',' << jsonFloat(v.pose.orientation.z) << ','
                  << jsonFloat(v.pose.orientation.w) << "],\"fov\":[" << jsonFloat(v.fov.angleLeft) << ','
                  << jsonFloat(v.fov.angleRight) << ',' << jsonFloat(v.fov.angleUp) << ',' << jsonFloat(v.fov.angleDown) << "]}";
            }
            o << ']';
        }
        if (io.frameViews) io.frameViews(located == XR_SUCCESS, head, views);

        // Render both eyes. A failure here leaves the frame begun; the loop stops and the session is
        // destroyed by the parent (legal), never ended with zero layers.
        o << ",\"eyes\":[";
        for (unsigned eye = 0; eye < 2; ++eye) {
            uint32_t imageIndex = 0;
            auto acquireInfo = xrStruct<XrSwapchainImageAcquireInfo>(XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO);
            const auto acquired = acquireImage(swapchains[eye], &acquireInfo, &imageIndex);
            o << (eye ? "," : "") << "{\"acquire\":" << int(acquired);
            if (acquired != XR_SUCCESS || imageIndex >= images[eye].size()) {
                emit(o.str() + "}]}", true); summary.failure = "xrAcquireSwapchainImage"; return false;
            }
            auto imageWait = xrStruct<XrSwapchainImageWaitInfo>(XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO);
            imageWait.timeout = 100000000;  // 100 ms
            const auto ready = waitImage(swapchains[eye], &imageWait);
            o << ",\"wait\":" << int(ready);
            if (ready != XR_SUCCESS) { emit(o.str() + "}]}", true); summary.failure = "xrWaitSwapchainImage"; return false; }
            const bool drawn = !io.drawEye || io.drawEye(eye, images[eye][imageIndex].image, limits.width, limits.height, flags);
            auto releaseInfo = xrStruct<XrSwapchainImageReleaseInfo>(XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO);
            const auto released = releaseImage(swapchains[eye], &releaseInfo);
            o << ",\"drawn\":" << (drawn ? "true" : "false") << ",\"release\":" << int(released) << '}';
            if (released != XR_SUCCESS) { emit(o.str() + "]}", true); summary.failure = "xrReleaseSwapchainImage"; return false; }
        }
        o << ']';

        std::array<XrCompositionLayerProjectionView, 2> projected{};
        for (unsigned eye = 0; eye < 2; ++eye) {
            projected[eye].type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
            projected[eye].pose = views[eye].pose;
            projected[eye].fov = views[eye].fov;
            projected[eye].subImage.swapchain = swapchains[eye];
            projected[eye].subImage.imageRect.extent = {int32_t(limits.width), int32_t(limits.height)};
            projected[eye].subImage.imageArrayIndex = 0;
        }
        auto layer = xrStruct<XrCompositionLayerProjection>(XR_TYPE_COMPOSITION_LAYER_PROJECTION);
        layer.space = viewSpace;
        layer.viewCount = 2;
        layer.views = projected.data();
        const XrCompositionLayerBaseHeader* layers[] = {reinterpret_cast<const XrCompositionLayerBaseHeader*>(&layer)};
        auto endInfo = xrStruct<XrFrameEndInfo>(XR_TYPE_FRAME_END_INFO);
        endInfo.displayTime = time;
        endInfo.environmentBlendMode = blend;
        endInfo.layerCount = 1;
        endInfo.layers = layers;
        // The first frames are made durable before End so a native crash inside End is attributable.
        if (index < 3) emit("{\"t\":\"pending\"" + o.str().substr(std::strlen("{\"t\":\"frame\"")) + ",\"call\":\"xrEndFrame(one_projection_layer)\"}", true);
        const auto ended = endFrame(session, &endInfo);
        o << ",\"end\":" << int(ended) << '}';
        const std::string flagText = std::to_string(flags);
        const bool poseChanged = located == XR_SUCCESS && pose != lastPose;
        if (poseChanged && !lastPose.empty()) ++summary.poseChanges;
        // Non-success results always reach the journal (the failure paths above emit on their own).
        const bool unusual = located != XR_SUCCESS || viewed != XR_SUCCESS || ended != XR_SUCCESS || begun != XR_SUCCESS;
        if (index < limits.fullJournalFrames || poseChanged || flagText != lastFlags || stepText != lastStep || unusual ||
            (limits.sampleEvery && index % limits.sampleEvery == 0)) {
            emit(o.str(), index < 3 || index % 300 == 0);
            ++summary.framesJournaled;
        }
        if (located == XR_SUCCESS) lastPose = pose;
        lastFlags = flagText;
        lastStep = stepText;
        if (ended != XR_SUCCESS && ended != XR_SESSION_LOSS_PENDING) { summary.failure = "xrEndFrame"; return false; }
        ++summary.framesEnded;
        return true;
    }

    void loop() {
        int64_t exitRequestedMs = 0;
        for (uint32_t index = 0;; ++index) {
            pollAll();
            if (summary.stoppingObserved) { summary.endReason = summary.exitRequested ? "STOPPING_AFTER_EXIT_REQUEST" : "RUNTIME_STOPPING"; return; }
            if (lost()) { summary.endReason = summary.exitRequested ? "EXITING_AFTER_EXIT_REQUEST" : "SESSION_LOST_OR_EXITING"; return; }
            if (!summary.exitRequested) {
                const char* why = cancelled() ? "CANCELLED" : elapsedMs() >= limits.durationMs ? "DURATION" :
                                  index >= limits.maxFrames ? "FRAME_LIMIT" : nullptr;
                if (why) {
                    emit(std::string("{\"t\":\"exit_request\",\"why\":\"") + why + "\",\"hostNs\":" + std::to_string(now()) + "}", true);
                    const auto r = requestExit(session);
                    setup("xrRequestExitSession", r);
                    if (r != XR_SUCCESS) { summary.endReason = "EXIT_REQUEST_FAILED"; return; }
                    summary.exitRequested = true;
                    exitRequestedMs = elapsedMs();
                }
            } else if (elapsedMs() - exitRequestedMs > limits.stopTimeoutMs) {
                summary.endReason = "NO_STOPPING_AFTER_EXIT_REQUEST"; return;
            }
            if (!frame(index)) { summary.endReason = "FRAME_FAILURE"; return; }
        }
    }

    void cleanup() {
        // Cleanup always runs to the end; a broken journal must not leave children alive.
        auto tidy = [&](const std::string& name, XrResult r) {
            try { setup(name, r); } catch (const Stop&) { summary.setupOperations.push_back({name, r, resultName(r)}); }
            if (r != XR_SUCCESS) summary.cleanupOk = false;
        };
        try {
            if (summary.sessionBegun && !summary.stoppingObserved && !lost() && summary.exitRequested) {
                // Give the runtime a short, bounded chance to deliver STOPPING after the last frame.
                for (unsigned i = 0; i < 10 && !summary.stoppingObserved && !lost(); ++i) { pollAll(); if (io.idle) io.idle(); }
            }
            if (summary.sessionBegun && summary.lastState == XR_SESSION_STATE_STOPPING) {
                tidy("xrEndSession", endSession(session));
                summary.sessionEnded = summary.cleanupOk;
            }
        } catch (const Stop& s) { summary.cleanupOk = false; if (summary.failure.empty()) summary.failure = s.reason; }
        for (unsigned eye = 0; eye < 2; ++eye)
            if (swapchains[eye] != XR_NULL_HANDLE) { tidy(eye ? "xrDestroySwapchain(right)" : "xrDestroySwapchain(left)", destroySwapchain(swapchains[eye])); swapchains[eye] = XR_NULL_HANDLE; }
        if (viewSpace != XR_NULL_HANDLE) { tidy("xrDestroySpace(VIEW)", destroySpace(viewSpace)); viewSpace = XR_NULL_HANDLE; }
        if (localSpace != XR_NULL_HANDLE) { tidy("xrDestroySpace(LOCAL)", destroySpace(localSpace)); localSpace = XR_NULL_HANDLE; }
    }

public:
    FrameLoop(PFN_xrGetInstanceProcAddr p, XrInstance i, XrSession s, XrEnvironmentBlendMode mode, FrameLoopLimits l, FrameLoopIo callbacks)
        : getProc(p), instance(i), session(s), blend(mode), limits(l), io(std::move(callbacks)) {}

    FrameLoopSummary execute() {
        startNs = now();
        summary.status = "RUNNING";
        try {
            if (!getProc || instance == XR_NULL_HANDLE || session == XR_NULL_HANDLE) throw Stop{"MissingParent"};
            prepare();
            loop();
        } catch (const Stop& s) {
            if (summary.failure.empty()) summary.failure = s.reason;
            if (summary.endReason.empty()) summary.endReason = "SETUP_OR_EVENT_FAILURE";
        } catch (...) {
            if (summary.failure.empty()) summary.failure = "FrameLoopException";
        }
        try { cleanup(); } catch (...) { summary.cleanupOk = false; }
        summary.status = summary.failure.empty() && summary.cleanupOk ? "COMPLETED" : "ERROR";
        try { emit("{\"t\":\"summary\",\"summary\":" + summary.toJson() + "}", true); } catch (const Stop&) {}
        return summary;
    }
    const FrameLoopSummary& result() const { return summary; }
};
}  // namespace xrspatial
