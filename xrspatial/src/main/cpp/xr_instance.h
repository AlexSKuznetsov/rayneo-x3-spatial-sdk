#pragma once
// OpenXR instance + HMD system for the RayNeo X3 Pro runtime (host-testable; no JNI here).
//
// Sequence verified on the device (docs/x3-pro-runtime-notes.md):
//   xrInitializeLoaderKHR(Android VM + application context)
//   -> xrCreateInstance with XrInstanceCreateInfoAndroidKHR chained ALWAYS. The runtime does not advertise
//      XR_KHR_android_create_instance, but without the chain xrCreateInstance crashes; the name is enabled
//      only when it is advertised.
//   -> xrGetSystem(HMD) -> PRIMARY_STEREO view sizes and blend mode.
// The loader functions come from the vendor loader library that the RayNeo SDK already loaded.
#include "xr_common.h"
#include <jni.h>  // openxr_platform.h needs the JNI types declared first
#include <openxr/openxr_platform.h>
#include <algorithm>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

namespace xrspatial {
struct LoaderFunctions {
    PFN_xrGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_xrEnumerateInstanceExtensionProperties enumerateExtensions = nullptr;
    PFN_xrCreateInstance createInstance = nullptr;
    PFN_xrDestroyInstance destroyInstance = nullptr;
    PFN_xrGetInstanceProperties getInstanceProperties = nullptr;
    PFN_xrGetSystem getSystem = nullptr;
    PFN_xrGetSystemProperties getSystemProperties = nullptr;
    PFN_xrEnumerateViewConfigurationViews enumerateViewConfigurationViews = nullptr;
    PFN_xrEnumerateEnvironmentBlendModes enumerateEnvironmentBlendModes = nullptr;
    bool complete() const {
        return getInstanceProcAddr && enumerateExtensions && createInstance && destroyInstance && getInstanceProperties &&
               getSystem && getSystemProperties && enumerateViewConfigurationViews && enumerateEnvironmentBlendModes;
    }
};

struct AndroidHandles { void* vm = nullptr; void* applicationContext = nullptr; void* activity = nullptr; };

struct InstanceInfo {
    std::string failure;  // empty on success: the failing operation's name otherwise
    std::vector<Operation> operations;
    std::string runtimeName, runtimeVersion, systemName;
    bool positionTrackingAdvertised = false, orientationTrackingAdvertised = false;
    uint32_t viewWidth = 0, viewHeight = 0;
    XrEnvironmentBlendMode blendMode = XR_ENVIRONMENT_BLEND_MODE_ADDITIVE;
};

class XrInstanceSetup {
public:
    XrInstanceSetup(const LoaderFunctions& functions, AndroidHandles android, std::string applicationName)
        : xr(functions), handles(android), appName(std::move(applicationName)) {}
    ~XrInstanceSetup() { destroy(); }
    XrInstanceSetup(const XrInstanceSetup&) = delete;
    XrInstanceSetup& operator=(const XrInstanceSetup&) = delete;

    // Returns false with info().failure set; owned handles are released by destroy()/the destructor.
    bool create() {
        if (!xr.complete()) return fail("LoaderFunctionsMissing");
        if (!handles.vm || !handles.applicationContext || !handles.activity) return fail("MissingAndroidHandles");
        PFN_xrVoidFunction raw = nullptr;
        if (!require("xrGetInstanceProcAddr(xrInitializeLoaderKHR)", xr.getInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", &raw))) return false;
        if (!raw) return fail("xrInitializeLoaderKHR:null");
        XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loaderInfo.applicationVM = handles.vm;
        loaderInfo.applicationContext = handles.applicationContext;
        if (!require("xrInitializeLoaderKHR", reinterpret_cast<PFN_xrInitializeLoaderKHR>(raw)(
                reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&loaderInfo)))) return false;

        uint32_t count = 0;
        if (!require("xrEnumerateInstanceExtensionProperties(count)", xr.enumerateExtensions(nullptr, 0, &count, nullptr))) return false;
        std::vector<XrExtensionProperties> extensions(count, XrExtensionProperties{XR_TYPE_EXTENSION_PROPERTIES});
        if (count && !require("xrEnumerateInstanceExtensionProperties(data)", xr.enumerateExtensions(nullptr, count, &count, extensions.data()))) return false;
        extensions.resize(std::min<size_t>(count, extensions.size()));
        const bool androidAdvertised = std::any_of(extensions.begin(), extensions.end(), [](const XrExtensionProperties& e) {
            return std::strcmp(e.extensionName, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) == 0;
        });

        XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        androidInfo.applicationVM = handles.vm;
        androidInfo.applicationActivity = handles.activity;
        const char* enabled[] = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
        XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
        createInfo.next = &androidInfo;  // always: see the header comment
        std::strncpy(createInfo.applicationInfo.applicationName, appName.c_str(), XR_MAX_APPLICATION_NAME_SIZE - 1);
        std::strncpy(createInfo.applicationInfo.engineName, "xrspatial", XR_MAX_ENGINE_NAME_SIZE - 1);
        createInfo.applicationInfo.applicationVersion = 1;
        createInfo.applicationInfo.apiVersion = XR_API_VERSION_1_0;
        createInfo.enabledExtensionCount = androidAdvertised ? 1u : 0u;
        createInfo.enabledExtensionNames = androidAdvertised ? enabled : nullptr;
        if (!require("xrCreateInstance", xr.createInstance(&createInfo, &instance_))) { instance_ = XR_NULL_HANDLE; return false; }
        if (instance_ == XR_NULL_HANDLE) return fail("xrCreateInstance:null");

        XrInstanceProperties properties{XR_TYPE_INSTANCE_PROPERTIES};
        if (!require("xrGetInstanceProperties", xr.getInstanceProperties(instance_, &properties))) return false;
        info_.runtimeName = properties.runtimeName;
        info_.runtimeVersion = std::to_string(XR_VERSION_MAJOR(properties.runtimeVersion)) + "." +
                               std::to_string(XR_VERSION_MINOR(properties.runtimeVersion)) + "." +
                               std::to_string(XR_VERSION_PATCH(properties.runtimeVersion));

        XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
        systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        if (!require("xrGetSystem(HMD)", xr.getSystem(instance_, &systemInfo, &system_))) return false;
        if (system_ == XR_NULL_SYSTEM_ID) return fail("xrGetSystem:null");
        XrSystemProperties systemProperties{XR_TYPE_SYSTEM_PROPERTIES};
        if (!require("xrGetSystemProperties", xr.getSystemProperties(instance_, system_, &systemProperties))) return false;
        info_.systemName = systemProperties.systemName;
        info_.positionTrackingAdvertised = systemProperties.trackingProperties.positionTracking == XR_TRUE;
        info_.orientationTrackingAdvertised = systemProperties.trackingProperties.orientationTracking == XR_TRUE;

        const auto stereo = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        uint32_t viewCount = 0;
        if (!require("xrEnumerateViewConfigurationViews(count)", xr.enumerateViewConfigurationViews(instance_, system_, stereo, 0, &viewCount, nullptr))) return false;
        if (viewCount < 2 || viewCount > 16) return fail("StereoViewCount");
        std::vector<XrViewConfigurationView> views(viewCount, XrViewConfigurationView{XR_TYPE_VIEW_CONFIGURATION_VIEW});
        if (!require("xrEnumerateViewConfigurationViews(data)", xr.enumerateViewConfigurationViews(instance_, system_, stereo, viewCount, &viewCount, views.data()))) return false;
        info_.viewWidth = views[0].recommendedImageRectWidth;
        info_.viewHeight = views[0].recommendedImageRectHeight;
        if (!info_.viewWidth || !info_.viewHeight) { info_.viewWidth = 640; info_.viewHeight = 480; }  // X3 Pro per-eye size

        uint32_t blendCount = 0;
        if (!require("xrEnumerateEnvironmentBlendModes(count)", xr.enumerateEnvironmentBlendModes(instance_, system_, stereo, 0, &blendCount, nullptr))) return false;
        if (blendCount > 0 && blendCount <= 8) {
            std::vector<XrEnvironmentBlendMode> modes(blendCount);
            if (!require("xrEnumerateEnvironmentBlendModes(data)", xr.enumerateEnvironmentBlendModes(instance_, system_, stereo, blendCount, &blendCount, modes.data()))) return false;
            if (blendCount) info_.blendMode = modes[0];
        }
        return true;
    }

    void destroy() {
        if (instance_ == XR_NULL_HANDLE) return;
        const XrInstance owned = instance_;
        instance_ = XR_NULL_HANDLE;
        record("xrDestroyInstance", xr.destroyInstance(owned));
    }

    XrInstance instance() const { return instance_; }
    XrSystemId system() const { return system_; }
    PFN_xrGetInstanceProcAddr getInstanceProcAddr() const { return xr.getInstanceProcAddr; }
    const InstanceInfo& info() const { return info_; }

private:
    LoaderFunctions xr;
    AndroidHandles handles;
    std::string appName;
    XrInstance instance_ = XR_NULL_HANDLE;
    XrSystemId system_ = XR_NULL_SYSTEM_ID;
    InstanceInfo info_;

    void record(const std::string& name, XrResult r) { info_.operations.push_back({name, r, resultName(r)}); }
    bool require(const std::string& name, XrResult r) {
        record(name, r);
        if (r == XR_SUCCESS) return true;
        info_.failure = name;
        return false;
    }
    bool fail(const std::string& why) { info_.failure = why; return false; }
};
}  // namespace xrspatial
