#pragma once
// Optional XR_EXT_hand_tracking (experimental): one tracker per hand, located in LOCAL every frame.
// The X3 Pro runtime 1.1.7.9 advertises the extension; whether it tracks hands is what this probes.
// Standard OpenXR only (functions from xrGetInstanceProcAddr); host-testable with fake entry points.
#include "xr_common.h"
#include <array>
#include <string>
#include <vector>

namespace xrspatial {
// One hand in one frame, raw. palm / indexTip are only meaningful when their *Flags say so.
struct HandState {
    XrResult locate = XR_ERROR_HANDLE_INVALID;
    bool active = false;
    uint64_t palmFlags = 0, indexTipFlags = 0;
    XrVector3f palm{}, indexTip{};
};

class HandTracking {
public:
    // Creates both trackers; false (with failure()) when the functions or a tracker are unavailable.
    bool create(PFN_xrGetInstanceProcAddr getProc, XrInstance instance, XrSession session) {
        if (!resolve(getProc, instance)) return false;
        for (unsigned hand = 0; hand < 2; ++hand) {
            XrHandTrackerCreateInfoEXT info{XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT};
            info.hand = hand == 0 ? XR_HAND_LEFT_EXT : XR_HAND_RIGHT_EXT;
            info.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
            createResults[hand] = createTracker(session, &info, &trackers[hand]);
            if (createResults[hand] != XR_SUCCESS) {
                trackers[hand] = XR_NULL_HANDLE;
                failure_ = "xrCreateHandTrackerEXT:" + std::to_string(int(createResults[hand]));
                destroy();
                return false;
            }
        }
        return true;
    }

    // Both hands at [time] in [base]. The first non-success locate is kept in HandState::locate and
    // failure(), and stops hand locating for the rest of the session (never retried); the head loop goes on.
    std::array<HandState, 2> locate(XrSpace base, XrTime time) {
        std::array<HandState, 2> out{};
        for (unsigned hand = 0; hand < 2; ++hand) {
            if (trackers[hand] == XR_NULL_HANDLE || stopped) continue;
            XrHandJointsLocateInfoEXT info{XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT};
            info.baseSpace = base;
            info.time = time;
            std::array<XrHandJointLocationEXT, XR_HAND_JOINT_COUNT_EXT> joints{};
            XrHandJointLocationsEXT locations{XR_TYPE_HAND_JOINT_LOCATIONS_EXT};
            locations.jointCount = XR_HAND_JOINT_COUNT_EXT;
            locations.jointLocations = joints.data();
            auto& s = out[hand];
            s.locate = locateJoints(trackers[hand], &info, &locations);
            if (s.locate != XR_SUCCESS) {
                ++locateErrors[hand];
                failure_ = "xrLocateHandJointsEXT:" + std::to_string(int(s.locate));
                stopped = true;
                continue;
            }
            ++located[hand];
            s.active = locations.isActive == XR_TRUE;
            if (s.active) ++activeFrames[hand];
            s.palmFlags = joints[XR_HAND_JOINT_PALM_EXT].locationFlags;
            s.palm = joints[XR_HAND_JOINT_PALM_EXT].pose.position;
            s.indexTipFlags = joints[XR_HAND_JOINT_INDEX_TIP_EXT].locationFlags;
            s.indexTip = joints[XR_HAND_JOINT_INDEX_TIP_EXT].pose.position;
        }
        return out;
    }

    void destroy() {
        for (auto& tracker : trackers) {
            if (tracker != XR_NULL_HANDLE && destroyTracker) destroyResults.push_back(destroyTracker(tracker));
            tracker = XR_NULL_HANDLE;
        }
    }

    bool running() const { return trackers[0] != XR_NULL_HANDLE && !stopped; }
    const std::string& failure() const { return failure_; }

    std::string toJson() const {
        auto pair = [](long long left, long long right) { return "[" + std::to_string(left) + "," + std::to_string(right) + "]"; };
        std::string destroyed = "[";
        for (size_t i = 0; i < destroyResults.size(); ++i) destroyed += (i ? "," : "") + std::to_string(int(destroyResults[i]));
        return "{\"failure\":" + jsonQuote(failure_) + ",\"create\":" + pair(createResults[0], createResults[1]) +
               ",\"located\":" + pair(located[0], located[1]) + ",\"activeFrames\":" + pair(activeFrames[0], activeFrames[1]) +
               ",\"locateErrors\":" + pair(locateErrors[0], locateErrors[1]) + ",\"destroy\":" + destroyed + "]}";
    }

private:
    PFN_xrCreateHandTrackerEXT createTracker = nullptr;
    PFN_xrDestroyHandTrackerEXT destroyTracker = nullptr;
    PFN_xrLocateHandJointsEXT locateJoints = nullptr;
    std::array<XrHandTrackerEXT, 2> trackers{XR_NULL_HANDLE, XR_NULL_HANDLE};
    std::array<XrResult, 2> createResults{XR_ERROR_FUNCTION_UNSUPPORTED, XR_ERROR_FUNCTION_UNSUPPORTED};
    std::array<uint32_t, 2> located{}, activeFrames{}, locateErrors{};
    bool stopped = false;
    std::vector<XrResult> destroyResults;
    std::string failure_;

    bool resolve(PFN_xrGetInstanceProcAddr getProc, XrInstance instance) {
        PFN_xrVoidFunction fn = nullptr;
        if (getProc(instance, "xrCreateHandTrackerEXT", &fn) != XR_SUCCESS || !fn) return missing("xrCreateHandTrackerEXT");
        createTracker = reinterpret_cast<PFN_xrCreateHandTrackerEXT>(fn);
        if (getProc(instance, "xrDestroyHandTrackerEXT", &fn) != XR_SUCCESS || !fn) return missing("xrDestroyHandTrackerEXT");
        destroyTracker = reinterpret_cast<PFN_xrDestroyHandTrackerEXT>(fn);
        if (getProc(instance, "xrLocateHandJointsEXT", &fn) != XR_SUCCESS || !fn) return missing("xrLocateHandJointsEXT");
        locateJoints = reinterpret_cast<PFN_xrLocateHandJointsEXT>(fn);
        return true;
    }
    bool missing(const char* name) { failure_ = std::string("missing:") + name; return false; }
};
}  // namespace xrspatial
