#pragma once
// HOST ONLY fake XR_EXT_hand_tracking entry points for HandTracking (hand_tracking.h).
#include "hand_tracking.h"
#include <functional>
#include <stdexcept>

namespace hand_tracking_tests {
struct Fake {
    bool missingLocate = false, rightCreateFails = false, leftActive = true;
    XrResult locateResult = XR_SUCCESS;
    unsigned created = 0, destroyed = 0, locates = 0;
    XrSpace lastBase = XR_NULL_HANDLE;
    XrTime lastTime = 0;
} *fake;
const auto kInstance = reinterpret_cast<XrInstance>(0x41);
const auto kSession = reinterpret_cast<XrSession>(0x42);
const auto kLocal = reinterpret_cast<XrSpace>(0x43);
const auto kLeft = reinterpret_cast<XrHandTrackerEXT>(0x51);
const auto kRight = reinterpret_cast<XrHandTrackerEXT>(0x52);

inline XrResult XRAPI_CALL createTracker(XrSession session, const XrHandTrackerCreateInfoEXT* info, XrHandTrackerEXT* out) {
    if (session != kSession || info->type != XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT || info->handJointSet != XR_HAND_JOINT_SET_DEFAULT_EXT)
        throw std::runtime_error("CREATE_INFO");
    if (info->hand == XR_HAND_RIGHT_EXT && fake->rightCreateFails) return XR_ERROR_FEATURE_UNSUPPORTED;
    ++fake->created;
    *out = info->hand == XR_HAND_LEFT_EXT ? kLeft : kRight;
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL destroyTracker(XrHandTrackerEXT tracker) {
    if (tracker != kLeft && tracker != kRight) throw std::runtime_error("DESTROY");
    ++fake->destroyed;
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL locateJoints(XrHandTrackerEXT tracker, const XrHandJointsLocateInfoEXT* info, XrHandJointLocationsEXT* out) {
    if (info->type != XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT || out->type != XR_TYPE_HAND_JOINT_LOCATIONS_EXT ||
        out->jointCount != XR_HAND_JOINT_COUNT_EXT || !out->jointLocations) throw std::runtime_error("LOCATE_INFO");
    ++fake->locates;
    fake->lastBase = info->baseSpace;
    fake->lastTime = info->time;
    if (fake->locateResult != XR_SUCCESS) return fake->locateResult;
    const bool left = tracker == kLeft;
    out->isActive = left && fake->leftActive ? XR_TRUE : XR_FALSE;
    auto& palm = out->jointLocations[XR_HAND_JOINT_PALM_EXT];
    palm.locationFlags = left ? 3 : 0;
    palm.pose.position = {-0.2f, -0.3f, -0.4f};
    auto& tip = out->jointLocations[XR_HAND_JOINT_INDEX_TIP_EXT];
    tip.locationFlags = left ? 15 : 0;
    tip.pose.position = {-0.18f, -0.22f, -0.5f};
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL getProc(XrInstance instance, const char* name, PFN_xrVoidFunction* out) {
    if (instance != kInstance) throw std::runtime_error("INSTANCE");
    const std::string n(name);
    *out = n == "xrCreateHandTrackerEXT" ? reinterpret_cast<PFN_xrVoidFunction>(createTracker)
         : n == "xrDestroyHandTrackerEXT" ? reinterpret_cast<PFN_xrVoidFunction>(destroyTracker)
         : n == "xrLocateHandJointsEXT" && !fake->missingLocate ? reinterpret_cast<PFN_xrVoidFunction>(locateJoints)
         : nullptr;
    return *out ? XR_SUCCESS : XR_ERROR_FUNCTION_UNSUPPORTED;
}

inline void run(const std::function<void(bool, const char*)>& expect, const std::function<void(const char*)>& pass) {
    {
        Fake f; fake = &f;
        xrspatial::HandTracking hands;
        expect(hands.create(getProc, kInstance, kSession) && hands.running() && f.created == 2, "both trackers created");
        const auto both = hands.locate(kLocal, 16666667);
        expect(f.locates == 2 && f.lastBase == kLocal && f.lastTime == 16666667, "both hands located in the given space at the frame time");
        const auto& left = both[0];
        expect(left.locate == XR_SUCCESS && left.active && left.palmFlags == 3 && left.indexTipFlags == 15 &&
               left.palm.z == -0.4f && left.indexTip.x == -0.18f, "left hand raw joints passed on");
        expect(both[1].locate == XR_SUCCESS && !both[1].active && both[1].palmFlags == 0, "inactive right hand reported as returned");
        hands.destroy();
        expect(f.destroyed == 2 && !hands.running(), "both trackers destroyed");
        hands.destroy();
        expect(f.destroyed == 2, "destroy is idempotent");
        expect(hands.toJson() == "{\"failure\":\"\",\"create\":[0,0],\"located\":[1,1],\"activeFrames\":[1,0],\"locateErrors\":[0,0],\"destroy\":[0,0]}",
               "summary JSON");
        pass("hand_tracking_create_locate_destroy");
    }
    {
        Fake f; f.missingLocate = true; fake = &f;
        xrspatial::HandTracking hands;
        expect(!hands.create(getProc, kInstance, kSession) && hands.failure() == "missing:xrLocateHandJointsEXT" && f.created == 0,
               "missing function reported, nothing created");
        f.missingLocate = false; f.rightCreateFails = true;
        xrspatial::HandTracking partial;
        expect(!partial.create(getProc, kInstance, kSession) && partial.failure() == "xrCreateHandTrackerEXT:-8" &&
               f.created == 1 && f.destroyed == 1 && !partial.running(), "failed right tracker releases the left one");
        pass("hand_tracking_create_failures");
    }
    {
        Fake f; f.locateResult = XR_ERROR_TIME_INVALID; fake = &f;
        xrspatial::HandTracking hands;
        expect(hands.create(getProc, kInstance, kSession), "created");
        const auto first = hands.locate(kLocal, 1);
        expect(first[0].locate == XR_ERROR_TIME_INVALID && f.locates == 1 && !hands.running() &&
               hands.failure() == "xrLocateHandJointsEXT:-30", "first locate error kept raw and stops hand locating");
        hands.locate(kLocal, 2);
        expect(f.locates == 1, "no retry after a locate error");
        hands.destroy();
        expect(f.destroyed == 2, "trackers still destroyed after the error");
        pass("hand_tracking_locate_error_stops_without_retry");
    }
}
}  // namespace hand_tracking_tests
