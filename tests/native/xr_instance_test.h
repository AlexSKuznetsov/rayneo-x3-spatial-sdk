#pragma once
// HOST ONLY fake loader for XrInstanceSetup (xr_instance.h). Reproduces the observed device rule: the
// runtime does not advertise XR_KHR_android_create_instance, yet needs the Android create info chained.
#include "xr_instance.h"
#include <functional>
#include <stdexcept>

namespace xr_instance_tests {
struct Fake {
    bool advertiseAndroid = false, zeroViewSize = false;
    std::string failAt;
    bool initialized = false, chained = false, androidEnabled = false;
    unsigned destroyed = 0;
    XrResult call(const std::string& name) const { return name == failAt ? XR_ERROR_RUNTIME_FAILURE : XR_SUCCESS; }
} *fake;
const auto kInstance = reinterpret_cast<XrInstance>(0x31);

inline XrResult XRAPI_CALL init(const XrLoaderInitInfoBaseHeaderKHR* info) {
    const auto* android = reinterpret_cast<const XrLoaderInitInfoAndroidKHR*>(info);
    if (android->type != XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR || android->applicationVM != reinterpret_cast<void*>(0x11) ||
        android->applicationContext != reinterpret_cast<void*>(0x22)) throw std::runtime_error("LOADER_INFO");
    fake->initialized = true;
    return fake->call("xrInitializeLoaderKHR");
}
inline XrResult XRAPI_CALL getProc(XrInstance, const char* name, PFN_xrVoidFunction* out) {
    *out = std::string(name) == "xrInitializeLoaderKHR" ? reinterpret_cast<PFN_xrVoidFunction>(init) : nullptr;
    return *out ? XR_SUCCESS : XR_ERROR_FUNCTION_UNSUPPORTED;
}
inline XrResult XRAPI_CALL extensions(const char*, uint32_t cap, uint32_t* count, XrExtensionProperties* out) {
    if (!fake->initialized) throw std::runtime_error("ENUMERATE_BEFORE_INIT");
    *count = fake->advertiseAndroid ? 2 : 1;
    if (cap) {
        std::strcpy(out[0].extensionName, "XR_KHR_opengl_es_enable");
        if (fake->advertiseAndroid) std::strcpy(out[1].extensionName, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME);
    }
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL createInstance(const XrInstanceCreateInfo* info, XrInstance* out) {
    const auto* android = reinterpret_cast<const XrInstanceCreateInfoAndroidKHR*>(info->next);
    // Observed: without this chain the device runtime crashes inside xrCreateInstance.
    if (!android || android->type != XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR || android->applicationActivity != reinterpret_cast<void*>(0x33))
        throw std::runtime_error("MISSING_ANDROID_CREATE_INFO_CRASHES_DEVICE_RUNTIME");
    fake->chained = true;
    fake->androidEnabled = info->enabledExtensionCount == 1 &&
                           std::string(info->enabledExtensionNames[0]) == XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME;
    const XrResult r = fake->call("xrCreateInstance");
    if (r == XR_SUCCESS) *out = kInstance;
    return r;
}
inline XrResult XRAPI_CALL destroyInstance(XrInstance i) {
    if (i != kInstance) throw std::runtime_error("DESTROY");
    ++fake->destroyed;
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL instanceProperties(XrInstance, XrInstanceProperties* p) {
    std::strcpy(p->runtimeName, "Fake runtime");
    p->runtimeVersion = XR_MAKE_VERSION(1, 1, 7);
    return fake->call("xrGetInstanceProperties");
}
inline XrResult XRAPI_CALL getSystem(XrInstance, const XrSystemGetInfo* info, XrSystemId* out) {
    if (info->formFactor != XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY) throw std::runtime_error("FORM_FACTOR");
    *out = 7;
    return fake->call("xrGetSystem");
}
inline XrResult XRAPI_CALL systemProperties(XrInstance, XrSystemId, XrSystemProperties* p) {
    std::strcpy(p->systemName, "Fake glasses");
    p->trackingProperties.positionTracking = XR_TRUE;
    p->trackingProperties.orientationTracking = XR_TRUE;
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL views(XrInstance, XrSystemId, XrViewConfigurationType type, uint32_t cap, uint32_t* count, XrViewConfigurationView* out) {
    if (type != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) throw std::runtime_error("VIEW_CONFIG");
    *count = 2;
    for (uint32_t i = 0; i < cap; ++i) {
        out[i].recommendedImageRectWidth = fake->zeroViewSize ? 0 : 1280;
        out[i].recommendedImageRectHeight = fake->zeroViewSize ? 0 : 960;
    }
    return XR_SUCCESS;
}
inline XrResult XRAPI_CALL blendModes(XrInstance, XrSystemId, XrViewConfigurationType, uint32_t cap, uint32_t* count, XrEnvironmentBlendMode* out) {
    *count = 1;
    if (cap) out[0] = XR_ENVIRONMENT_BLEND_MODE_ADDITIVE;
    return XR_SUCCESS;
}

inline xrspatial::LoaderFunctions functions() {
    xrspatial::LoaderFunctions f;
    f.getInstanceProcAddr = getProc;
    f.enumerateExtensions = extensions;
    f.createInstance = createInstance;
    f.destroyInstance = destroyInstance;
    f.getInstanceProperties = instanceProperties;
    f.getSystem = getSystem;
    f.getSystemProperties = systemProperties;
    f.enumerateViewConfigurationViews = views;
    f.enumerateEnvironmentBlendModes = blendModes;
    return f;
}
inline xrspatial::AndroidHandles handles() {
    return {reinterpret_cast<void*>(0x11), reinterpret_cast<void*>(0x22), reinterpret_cast<void*>(0x33)};
}

inline void run(const std::function<void(bool, const char*)>& expect, const std::function<void(const char*)>& pass) {
    {
        Fake f; fake = &f;
        {
            xrspatial::XrInstanceSetup setup(functions(), handles(), "test");
            expect(setup.create() && setup.instance() == kInstance && setup.system() == 7, "instance and HMD system created");
            expect(f.chained && !f.androidEnabled, "Android create info chained although the extension is not advertised; name not enabled");
            const auto& info = setup.info();
            expect(info.failure.empty() && info.runtimeName == "Fake runtime" && info.runtimeVersion == "1.1.7" && info.systemName == "Fake glasses" &&
                   info.positionTrackingAdvertised && info.viewWidth == 1280 && info.viewHeight == 960 &&
                   info.blendMode == XR_ENVIRONMENT_BLEND_MODE_ADDITIVE, "runtime facts and recommended view size");
        }
        expect(f.destroyed == 1, "destructor destroys the instance once");
        pass("xr_instance_create_always_chains_android_info");
    }
    {
        Fake f; f.advertiseAndroid = true; f.zeroViewSize = true; fake = &f;
        xrspatial::XrInstanceSetup setup(functions(), handles(), "test");
        expect(setup.create() && f.androidEnabled, "advertised extension is enabled");
        expect(setup.info().viewWidth == 640 && setup.info().viewHeight == 480, "zero recommended size falls back to 640x480");
        pass("xr_instance_advertised_extension_and_size_fallback");
    }
    {
        Fake f; f.failAt = "xrCreateInstance"; fake = &f;
        {
            xrspatial::XrInstanceSetup setup(functions(), handles(), "test");
            expect(!setup.create() && setup.info().failure == "xrCreateInstance" && setup.instance() == XR_NULL_HANDLE, "failure keeps the operation name");
            const auto& last = setup.info().operations.back();
            expect(last.name == "xrCreateInstance" && last.result == XR_ERROR_RUNTIME_FAILURE && last.resultName == "XR_ERROR_RUNTIME_FAILURE",
                   "raw XrResult and its name recorded");
        }
        expect(f.destroyed == 0, "nothing to destroy after a failed create");
        pass("xr_instance_failure_recorded_raw");
    }
    {
        Fake f; f.failAt = "xrGetSystem"; fake = &f;
        {
            xrspatial::XrInstanceSetup setup(functions(), handles(), "test");
            expect(!setup.create() && setup.info().failure == "xrGetSystem(HMD)", "system failure reported");
        }
        expect(f.destroyed == 1, "instance destroyed after a later failure");
        xrspatial::XrInstanceSetup missing(functions(), xrspatial::AndroidHandles{}, "test");
        expect(!missing.create() && missing.info().failure == "MissingAndroidHandles", "missing Android handles rejected before any call");
        pass("xr_instance_cleanup_after_failure");
    }
}
}  // namespace xr_instance_tests
