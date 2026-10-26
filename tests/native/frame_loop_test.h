#pragma once
// HOST ONLY fake OpenXR runtime for the frame loop. Never linked into the library.
// It reproduces the two observed device behaviours: Wait without End -> -37, zero-layer End -> crash.
#include "frame_loop.h"
#include <cstring>
#include <functional>
#include <stdexcept>

namespace frame_loop_tests {
struct Fake {
    XrInstance instance = reinterpret_cast<XrInstance>(0x51);
    XrSession session = reinterpret_cast<XrSession>(0x52);
    XrSwapchain chains[2] = {reinterpret_cast<XrSwapchain>(0x61), reinterpret_cast<XrSwapchain>(0x62)};
    XrSpace local = reinterpret_cast<XrSpace>(0x71), view = reinterpret_cast<XrSpace>(0x72);
    int64_t clockNs = 0, time = 16666667, step = 16666667;
    bool sendReady = true, ready = false, begun = false, frameOpen = false, exitRequested = false;
    int state = 0, pendingState = 0;
    std::vector<int> queued;
    unsigned waits = 0, begins = 0, ends = 0, locates = 0, sessionEnds = 0, destroyedChains = 0, destroyedSpaces = 0;
    unsigned acquired[2] = {}, released[2] = {}, stoppingAfterExitFrames = 2, framesSinceExit = 0;
    unsigned endErrorAt = 0, runtimeStopAt = 0, cancelAt = 0;
    uint64_t flags = 15;
    uint32_t viewFlags = XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT;
    bool cancelled = false, constantTime = false, frozenPose = false;
    bool exitingInsteadOfStopping = false;  // observed on device: EXITING then FOCUSED, never STOPPING
    std::vector<int64_t> endTimes;
} *fake;

inline void push(int s) { fake->queued.push_back(s); }
inline XrResult XRAPI_CALL poll(XrInstance i, XrEventDataBuffer* out) {
    auto& f = *fake;
    if (i != f.instance) throw std::runtime_error("instance");
    if (f.sendReady && !f.ready) { f.ready = true; push(XR_SESSION_STATE_READY); }
    if (f.queued.empty()) return XR_EVENT_UNAVAILABLE;
    XrEventDataSessionStateChanged e{};
    e.type = XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED;
    e.session = f.session;
    e.state = XrSessionState(f.queued.front());
    e.time = f.time;
    f.state = f.queued.front();
    f.queued.erase(f.queued.begin());
    std::memcpy(out, &e, sizeof e);
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL beginSession(XrSession, const XrSessionBeginInfo* info) {
    auto& f = *fake;
    if (f.state != XR_SESSION_STATE_READY || info->primaryViewConfigurationType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_SESSION_NOT_READY;
    f.begun = true;
    push(XR_SESSION_STATE_SYNCHRONIZED); push(XR_SESSION_STATE_VISIBLE); push(XR_SESSION_STATE_FOCUSED);
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL endSession(XrSession) {
    auto& f = *fake;
    if (f.state != XR_SESSION_STATE_STOPPING) return XR_ERROR_SESSION_NOT_STOPPING;
    ++f.sessionEnds;
    push(XR_SESSION_STATE_IDLE); push(XR_SESSION_STATE_EXITING);
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL requestExit(XrSession) { fake->exitRequested = true; return XR_SUCCESS; }
inline XrResult XRAPI_CALL enumerateSpaces(XrSession, uint32_t cap, uint32_t* count, XrReferenceSpaceType* out) {
    *count = 3;
    if (cap) { out[0] = XR_REFERENCE_SPACE_TYPE_VIEW; out[1] = XR_REFERENCE_SPACE_TYPE_LOCAL; out[2] = XR_REFERENCE_SPACE_TYPE_STAGE; }
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL createSpace(XrSession, const XrReferenceSpaceCreateInfo* info, XrSpace* out) {
    *out = info->referenceSpaceType == XR_REFERENCE_SPACE_TYPE_LOCAL ? fake->local : fake->view;
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL destroySpace(XrSpace) { ++fake->destroyedSpaces; return XR_SUCCESS; }
inline XrResult XRAPI_CALL enumerateFormats(XrSession, uint32_t cap, uint32_t* count, int64_t* out) {
    *count = 2;
    if (cap) { out[0] = 35907; out[1] = 32856; }
    return XR_SUCCESS;
}
inline unsigned eyeOf(XrSwapchain s) { return s == fake->chains[1] ? 1u : 0u; }
inline XrResult XRAPI_CALL createSwapchain(XrSession, const XrSwapchainCreateInfo* info, XrSwapchain* out) {
    static unsigned next = 0;
    if (info->format != 35907 || info->sampleCount != 1 || info->width != 640 || info->height != 480) return XR_ERROR_VALIDATION_FAILURE;
    *out = fake->chains[next++ % 2];
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL destroySwapchain(XrSwapchain) { ++fake->destroyedChains; return XR_SUCCESS; }
inline XrResult XRAPI_CALL enumerateImages(XrSwapchain, uint32_t cap, uint32_t* count, XrSwapchainImageBaseHeader* out) {
    *count = 3;
    if (cap) for (uint32_t i = 0; i < 3; ++i) reinterpret_cast<xrspatial::SwapchainGlesImage*>(out)[i].image = 10 + i;
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL acquire(XrSwapchain s, const XrSwapchainImageAcquireInfo*, uint32_t* index) { *index = fake->acquired[eyeOf(s)]++ % 3; return XR_SUCCESS; }
inline XrResult XRAPI_CALL waitImage(XrSwapchain, const XrSwapchainImageWaitInfo* info) { return info->timeout > 0 ? XR_SUCCESS : XR_TIMEOUT_EXPIRED; }
inline XrResult XRAPI_CALL release(XrSwapchain s, const XrSwapchainImageReleaseInfo*) { ++fake->released[eyeOf(s)]; return XR_SUCCESS; }
inline XrResult XRAPI_CALL waitFrame(XrSession, const XrFrameWaitInfo*, XrFrameState* out) {
    auto& f = *fake;
    if (!f.begun) return XR_ERROR_SESSION_NOT_RUNNING;
    if (f.frameOpen) return XR_ERROR_CALL_ORDER_INVALID;  // observed device behaviour
    ++f.waits;
    if (f.waits > 1 && !f.constantTime) f.time += f.step;
    f.clockNs += f.step;
    out->predictedDisplayTime = f.time;
    out->predictedDisplayPeriod = f.step;
    out->shouldRender = XR_TRUE;
    if (f.cancelAt && f.waits == f.cancelAt) f.cancelled = true;
    if (f.runtimeStopAt && f.waits == f.runtimeStopAt) push(XR_SESSION_STATE_STOPPING);
    if (f.exitRequested && ++f.framesSinceExit == f.stoppingAfterExitFrames) {
        if (f.exitingInsteadOfStopping) { push(XR_SESSION_STATE_EXITING); push(XR_SESSION_STATE_FOCUSED); }
        else push(XR_SESSION_STATE_STOPPING);
    }
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL beginFrame(XrSession, const XrFrameBeginInfo* info) {
    if (!info || info->type != XR_TYPE_FRAME_BEGIN_INFO) throw std::runtime_error("BEGIN_INFO");
    fake->frameOpen = true; ++fake->begins; return XR_SUCCESS;
}
inline XrResult XRAPI_CALL endFrame(XrSession, const XrFrameEndInfo* info) {
    auto& f = *fake;
    if (info->layerCount == 0) throw std::runtime_error("ZERO_LAYER_END_FRAME_CRASHES_DEVICE_RUNTIME");
    const auto* layer = reinterpret_cast<const XrCompositionLayerProjection*>(info->layers[0]);
    if (layer->type != XR_TYPE_COMPOSITION_LAYER_PROJECTION || layer->viewCount != 2 || layer->space != f.view) throw std::runtime_error("LAYER");
    for (unsigned eye = 0; eye < 2; ++eye) {
        const auto& v = layer->views[eye];
        if (v.type != XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW || v.subImage.swapchain != f.chains[eye] || v.subImage.imageRect.extent.width != 640) throw std::runtime_error("VIEW");
        if (f.released[eye] != f.ends + 1) throw std::runtime_error("UNRELEASED_IMAGE");
    }
    if (info->displayTime != f.time || info->environmentBlendMode != XR_ENVIRONMENT_BLEND_MODE_ADDITIVE) throw std::runtime_error("END_TIME");
    f.frameOpen = false;
    ++f.ends;
    f.endTimes.push_back(info->displayTime);
    if (f.endErrorAt && f.ends == f.endErrorAt) return XR_ERROR_RUNTIME_FAILURE;
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL locateViews(XrSession, const XrViewLocateInfo* info, XrViewState* state, uint32_t cap, uint32_t* count, XrView* views) {
    if (info->space != fake->view || info->displayTime != fake->time || cap != 2) throw std::runtime_error("LOCATE_VIEWS");
    *count = 2;
    state->viewStateFlags = fake->viewFlags;
    for (unsigned i = 0; i < 2; ++i) { views[i].pose.orientation.w = 1; views[i].pose.position.x = i ? 0.03f : -0.03f; views[i].fov = {-0.4f, 0.4f, 0.3f, -0.3f}; }
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL locateSpace(XrSpace space, XrSpace base, XrTime time, XrSpaceLocation* out) {
    auto& f = *fake;
    if (space != f.view || base != f.local || time != f.time) throw std::runtime_error("LOCATE_SPACE");
    ++f.locates;
    out->locationFlags = f.flags;
    out->pose.orientation = {0, 0, 0, 1};
    out->pose.position = {f.frozenPose ? 0.f : 0.001f * float(f.locates), 0.f, 0.f};
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL getProc(XrInstance, const char* name, PFN_xrVoidFunction* out) {
    const std::string n(name);
#define ENTRY(s, fn) if (n == s) { *out = reinterpret_cast<PFN_xrVoidFunction>(fn); return XR_SUCCESS; }
    ENTRY("xrPollEvent", poll) ENTRY("xrBeginSession", beginSession) ENTRY("xrEndSession", endSession)
    ENTRY("xrRequestExitSession", requestExit) ENTRY("xrEnumerateReferenceSpaces", enumerateSpaces)
    ENTRY("xrCreateReferenceSpace", createSpace) ENTRY("xrDestroySpace", destroySpace)
    ENTRY("xrEnumerateSwapchainFormats", enumerateFormats) ENTRY("xrCreateSwapchain", createSwapchain)
    ENTRY("xrDestroySwapchain", destroySwapchain) ENTRY("xrEnumerateSwapchainImages", enumerateImages)
    ENTRY("xrAcquireSwapchainImage", acquire) ENTRY("xrWaitSwapchainImage", waitImage) ENTRY("xrReleaseSwapchainImage", release)
    ENTRY("xrWaitFrame", waitFrame) ENTRY("xrBeginFrame", beginFrame) ENTRY("xrEndFrame", endFrame)
    ENTRY("xrLocateViews", locateViews) ENTRY("xrLocateSpace", locateSpace)
#undef ENTRY
    *out = nullptr;
    return XR_ERROR_FUNCTION_UNSUPPORTED;
}

inline xrspatial::FrameLoopSummary runLoop(Fake& f, std::vector<std::string>& lines, int64_t durationMs = 1000,
                                         std::function<bool(const std::string&, bool)> emit = {},
                                         std::function<void(const xrspatial::LocatedPose&)> sample = {},
                                         decltype(xrspatial::FrameLoopIo::frameViews) frameViews = {},
                                         decltype(xrspatial::FrameLoopIo::locateExtra) locateExtra = {}) {
    fake = &f;
    xrspatial::FrameLoopLimits limits;
    limits.durationMs = durationMs;
    limits.readyTimeoutMs = 200;
    xrspatial::FrameLoopIo io;
    io.emit = emit ? emit : [&](const std::string& line, bool) { lines.push_back(line); return true; };
    io.cancelled = [&] { return f.cancelled; };
    io.monotonicNs = [&] { return f.clockNs; };
    io.idle = [&] { f.clockNs += 10000000; };
    io.sample = sample;
    io.frameViews = frameViews;
    io.locateExtra = locateExtra;
    unsigned draws = 0;
    io.drawEye = [&](unsigned, uint32_t image, uint32_t w, uint32_t h, uint64_t) { ++draws; return image >= 10 && w == 640 && h == 480; };
    xrspatial::FrameLoop probe(getProc, f.instance, f.session, XR_ENVIRONMENT_BLEND_MODE_ADDITIVE, limits, io);
    auto summary = probe.execute();
    if (draws != 2 * summary.framesBegun && summary.failure.empty()) throw std::runtime_error("DRAW_COUNT");
    return summary;
}
inline unsigned count(const std::vector<std::string>& lines, const std::string& needle) {
    unsigned n = 0;
    for (const auto& l : lines) if (l.find(needle) != std::string::npos) ++n;
    return n;
}

inline void run(const std::function<void(bool, const char*)>& expect, const std::function<void(const char*)>& pass) {
    {
        Fake f; std::vector<std::string> lines;
        auto s = runLoop(f, lines);
        expect(s.status == "COMPLETED" && s.endReason == "STOPPING_AFTER_EXIT_REQUEST", "normal run ends via exit request");
        expect(s.framesBegun == s.framesEnded && f.begins == f.ends && s.framesEnded >= 55, "every begun frame ended with a layer");
        expect(s.timeStrictlyIncreasing && s.minDelta == f.step && s.maxDelta == f.step && s.firstDisplayTime == 16666667, "raw XrTime progression recorded");
        expect(s.allFourFlags == s.locatesReturned && s.flagCounts.at(15) == s.locatesReturned, "flags counted raw");
        expect(f.sessionEnds == 1 && f.destroyedChains == 2 && f.destroyedSpaces == 2 && s.cleanupOk, "EndSession only after STOPPING, all children destroyed");
        expect(count(lines, "\"t\":\"frame\"") == s.framesWaited && count(lines, "\"t\":\"pending\"") == 3 && count(lines, "\"t\":\"summary\"") == 1, "journal has every frame, durable pre-End markers, summary");
        expect(lines.back().find("\"timeDomain\":\"XR_TIME_RAW_NOT_CONVERTED\"") != std::string::npos, "time kept as raw XrTime");
        pass("frame_loop_normal_loop_projection_layer_every_frame_clean_exit");
    }
    {
        Fake f; f.cancelAt = 10; std::vector<std::string> lines;
        auto s = runLoop(f, lines, 60000);
        expect(s.status == "COMPLETED" && s.exitRequested && f.sessionEnds == 1 && s.framesWaited < 20, "cancel requests exit and ends cleanly");
        expect(count(lines, "\"why\":\"CANCELLED\"") == 1, "cancel reason journaled");
        pass("frame_loop_cancel_requests_exit_then_ends_session");
    }
    {
        Fake f; f.endErrorAt = 5; std::vector<std::string> lines;
        auto s = runLoop(f, lines);
        expect(s.status == "ERROR" && s.failure == "xrEndFrame" && s.framesEnded == 4 && f.waits == 5, "End error stops the loop, no retry");
        expect(f.sessionEnds == 0 && f.destroyedChains == 2 && f.destroyedSpaces == 2, "no EndSession without STOPPING; children destroyed");
        pass("frame_loop_end_frame_error_stops_without_retry");
    }
    {
        Fake f; f.runtimeStopAt = 8; std::vector<std::string> lines;
        auto s = runLoop(f, lines, 60000);
        expect(s.endReason == "RUNTIME_STOPPING" && !s.exitRequested && f.sessionEnds == 1 && s.status == "COMPLETED", "runtime-initiated STOPPING is honoured");
        pass("frame_loop_runtime_stopping_ends_session");
    }
    {
        Fake f; f.viewFlags = 0; std::vector<std::string> lines;
        auto s = runLoop(f, lines);
        expect(s.status == "COMPLETED" && s.viewFallbackFrames == s.framesBegun && count(lines, "DEFAULT_RENDER_ONLY") == s.framesWaited + 3, "invalid view poses still submit one layer (render-only defaults)");
        pass("frame_loop_invalid_views_render_only_default_layer");
    }
    {
        Fake f; f.flags = 0; std::vector<std::string> lines;
        auto s = runLoop(f, lines);
        expect(s.allFourFlags == 0 && s.flagCounts.at(0) == s.locatesReturned && count(lines, "\"p\":[") == 0 && count(lines, "\"q\":[") == 0, "invalid pose components never written");
        pass("frame_loop_untracked_flags_no_pose_components");
    }
    {
        Fake f; f.flags = 5; std::vector<std::string> lines;
        auto s = runLoop(f, lines);
        expect(s.allFourFlags == 0 && count(lines, "\"p\":[") == 0 && count(lines, "\"q\":[") == s.framesWaited + 3, "orientation-only flags keep orientation, no position");
        pass("frame_loop_orientation_only_flags_independent");
    }
    {
        Fake f; f.sendReady = false; std::vector<std::string> lines;
        auto s = runLoop(f, lines);
        expect(s.failure == "ReadyTimeout" && !s.sessionBegun && f.waits == 0 && f.destroyedChains == 0, "no READY -> no Begin, no frames");
        pass("frame_loop_ready_timeout_no_begin");
    }
    {
        Fake f; f.constantTime = true; std::vector<std::string> lines;
        auto s = runLoop(f, lines);
        expect(!s.timeStrictlyIncreasing && s.maxDelta == 0 && s.status == "COMPLETED", "non-increasing XrTime is reported, not repaired");
        pass("frame_loop_constant_time_reported_not_repaired");
    }
    {
        Fake f; std::vector<std::string> lines; unsigned n = 0;
        auto s = runLoop(f, lines, 1000, [&](const std::string&, bool) { return ++n < 40; });
        expect(s.status == "ERROR" && s.failure == "JournalWriteFailed" && f.waits < 10, "journal failure stops the loop");
        pass("frame_loop_journal_failure_stops");
    }
    {
        Fake f; f.stoppingAfterExitFrames = 100000; std::vector<std::string> lines;
        auto s = runLoop(f, lines, 500);
        expect(s.endReason == "NO_STOPPING_AFTER_EXIT_REQUEST" && f.sessionEnds == 0 && f.destroyedChains == 2, "bounded wait for STOPPING, then parent destroy");
        pass("frame_loop_no_stopping_bounded_then_destroy");
    }
    {
        Fake f; f.exitingInsteadOfStopping = true; std::vector<std::string> lines;
        auto s = runLoop(f, lines);
        expect(s.endReason == "EXITING_AFTER_EXIT_REQUEST" && s.exitingObserved && !s.stoppingObserved && s.lastState == XR_SESSION_STATE_FOCUSED,
               "transient EXITING is sticky even when FOCUSED follows in the same poll");
        expect(f.sessionEnds == 0 && f.destroyedChains == 2 && f.destroyedSpaces == 2 && s.cleanupOk && s.status == "COMPLETED", "no EndSession without STOPPING; children destroyed");
        expect(f.waits <= f.stoppingAfterExitFrames + 70, "loop stops at EXITING instead of running out the stop timeout");
        pass("frame_loop_exiting_without_stopping_stops_loop");
    }
    {
        Fake f; f.frozenPose = true; f.step = 1000000; std::vector<std::string> lines;
        auto s = runLoop(f, lines);
        const unsigned journaled = count(lines, "\"t\":\"frame\"");
        expect(s.framesWaited > 900 && s.poseChanges == 0 && journaled == s.framesJournaled, "frozen pose counted, journal count recorded");
        expect(journaled >= 300 + (s.framesWaited - 300) / 10 && journaled <= 300 + (s.framesWaited - 300) / 10 + 2, "after 300 frames only every 10th unchanged frame is journaled");
        pass("frame_loop_journal_decimates_unchanged_frames");
    }
    {
        Fake f; f.step = 1000000; std::vector<std::string> lines;
        auto s = runLoop(f, lines);
        expect(s.framesJournaled == s.framesWaited && s.poseChanges == s.framesWaited - 1, "changing poses are always journaled");
        pass("frame_loop_journal_keeps_every_pose_change");
    }
    {
        Fake f; f.flags = 7; std::vector<std::string> lines; std::vector<xrspatial::LocatedPose> samples;
        auto s = runLoop(f, lines, 1000, {}, [&](const xrspatial::LocatedPose& x) { samples.push_back(x); });
        bool raw = !samples.empty();
        for (size_t i = 0; i < samples.size(); ++i) {
            const auto& x = samples[i];
            raw = raw && x.index == i && x.flags == 7 && x.xrTime == 16666667 + int64_t(i) * f.step &&
                  x.pose.position.x == 0.001f * float(i + 1) && x.pose.orientation.w == 1.f && (i == 0 || x.hostReceiptNs > samples[i - 1].hostReceiptNs);
        }
        expect(samples.size() == s.locatesReturned, "one sample per successful locate");
        expect(raw, "sample keeps index, raw XrTime, raw flags (7, not repaired) and raw pose");
        pass("frame_loop_sample_callback_delivers_raw_locates");
    }
    {
        Fake f; std::vector<std::string> lines; unsigned calls = 0; bool raw = true;
        auto s = runLoop(f, lines, 1000, {}, {}, [&](bool located, const XrSpaceLocation& head, const std::array<XrView, 2>& views) {
            ++calls;
            raw = raw && located && head.locationFlags == 15 && head.pose.position.x == 0.001f * float(calls) &&
                  views[0].pose.position.x == -0.03f && views[1].pose.position.x == 0.03f && views[1].fov.angleUp == 0.3f;
        });
        expect(calls == s.framesBegun && raw, "frame views once per begun frame: raw head location and the layer's eye views");
        expect(count(lines, "\"eyeViews\":[{\"position\":[-0.0299999993") == 6, "eye geometry journaled in the first frames (pending + frame lines)");
        pass("frame_loop_frame_views_for_world_scene");
    }
    {
        Fake f; std::vector<std::string> lines; unsigned calls = 0; bool same = true;
        auto s = runLoop(f, lines, 1000, {}, {}, {}, [&](uint32_t index, XrSpace local, XrTime time) {
            same = same && index == calls && local == f.local && time == f.time;
            ++calls;
        });
        expect(calls == s.framesBegun && same, "extra locate once per begun frame with LOCAL and the frame's display time");
        pass("frame_loop_locate_extra_uses_loop_space_and_time");
    }
}
}  // namespace frame_loop_tests
