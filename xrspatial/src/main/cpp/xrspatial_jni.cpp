// JNI entry of the SDK: OpenXR instance + GLES session on the RayNeo runtime, the frame loop
// (frame_loop.h), per-eye render callbacks into Kotlin and the head-locked HUD.
// The vendor OpenXR loader must already be loaded (XrSpatialSession does that through the RayNeo SDK).
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <jni.h>
#include <openxr/openxr_platform.h>  // before frame_loop.h: GLES swapchain image type
#include "frame_loop.h"
#include "hud_text.h"
#include "render_math.h"
#include "xr_instance.h"
#include <android/log.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <chrono>
#include <ctime>
#include <dlfcn.h>
#include <limits>
#include <mutex>
#include <thread>

namespace {
const char* kTag = "XrSpatial";
std::atomic<bool> cancelled{false};
std::mutex runMutex;  // one session per process at a time
std::mutex hudMutex;
std::string hudTop, hudBottom, hudCentre;

int64_t monotonicNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

// Own EGL context with a window surface on the app's SurfaceView: without time-warp the RayNeo runtime
// presents inside xrEndFrame by swapping the calling thread's current draw surface. One framebuffer per
// eye plus a shared depth buffer.
class Gles {
public:
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    ANativeWindow* window = nullptr;
    std::array<GLuint, 2> fbo{};
    GLuint depth = 0;
    uint32_t depthWidth = 0, depthHeight = 0;
    std::string failure, version, surfaceText = "NONE";

    // Takes ownership of target; destroy() releases it on every path.
    bool create(int minor, ANativeWindow* target) {
        window = target;
        if (!window) return fail("NoWindow");
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY) return fail("eglGetDisplay");
        if (eglInitialize(display, nullptr, nullptr) != EGL_TRUE) return fail("eglInitialize");
        initialized = true;
        if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) return fail("eglBindAPI");
        const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                                     EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        EGLint count = 0;
        if (eglChooseConfig(display, attributes, &config, 1, &count) != EGL_TRUE || count != 1) return fail("eglChooseConfig");
        surface = eglCreateWindowSurface(display, config, window, nullptr);
        if (surface == EGL_NO_SURFACE) return fail("eglCreateWindowSurface");
        EGLint w = 0, h = 0;
        eglQuerySurface(display, surface, EGL_WIDTH, &w);
        eglQuerySurface(display, surface, EGL_HEIGHT, &h);
        surfaceText = "WINDOW " + std::to_string(w) + "x" + std::to_string(h);
        const EGLint contextAttributes[] = {EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, minor, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
        if (context == EGL_NO_CONTEXT) return fail("eglCreateContext");
        if (eglMakeCurrent(display, surface, surface, context) != EGL_TRUE) return fail("eglMakeCurrent");
        GLint major = 0, minorOut = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &major);
        glGetIntegerv(GL_MINOR_VERSION, &minorOut);
        version = std::to_string(major) + "." + std::to_string(minorOut);
        glGenFramebuffers(2, fbo.data());
        glGenRenderbuffers(1, &depth);
        if (glGetError() != GL_NO_ERROR) return fail("glGenFramebuffers");
        return true;
    }

    // Binds eye's framebuffer (colour = swapchain image, shared depth), viewport and clears to black.
    bool begin(unsigned eye, uint32_t image, uint32_t width, uint32_t height) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[eye]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, image, 0);
        if (depthWidth != width || depthHeight != height) {
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, GLsizei(width), GLsizei(height));
            glBindRenderbuffer(GL_RENDERBUFFER, 0);
            depthWidth = width;
            depthHeight = height;
        }
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return false;
        glViewport(0, 0, GLsizei(width), GLsizei(height));
        glDisable(GL_SCISSOR_TEST);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glDepthMask(GL_TRUE);
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClearDepthf(1.f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        return true;
    }

    // HUD text over whatever the app drew, then detach. The app's GL state is reset where the HUD needs it.
    bool end(const std::vector<xrspatial::HudRect>& text) {
        glDisable(GL_DEPTH_TEST);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        if (!text.empty()) {
            glEnable(GL_SCISSOR_TEST);
            glClearColor(0.85f, 0.85f, 0.85f, 1.f);
            for (const auto& r : text) {
                glScissor(r.x, r.y, r.w, r.h);
                glClear(GL_COLOR_BUFFER_BIT);
            }
            glDisable(GL_SCISSOR_TEST);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[1]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glFlush();
        return glGetError() == GL_NO_ERROR;
    }

    void destroy() {
        if (initialized) {
            if (context != EGL_NO_CONTEXT && eglGetCurrentContext() == context) {
                if (fbo[0] || fbo[1]) glDeleteFramebuffers(2, fbo.data());
                if (depth) glDeleteRenderbuffers(1, &depth);
            }
            fbo = {};
            depth = 0;
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            eglTerminate(display);
            eglReleaseThread();
            initialized = false;
            context = EGL_NO_CONTEXT;
            surface = EGL_NO_SURFACE;
        }
        if (window) ANativeWindow_release(window);
        window = nullptr;
    }

private:
    bool initialized = false;
    bool fail(const char* what) { failure = std::string(what) + ":" + std::to_string(eglGetError()); return false; }
};

// Static callbacks on io.github.alexskuznetsov.xrspatial.XrSpatialNative, resolved once per run.
struct JavaCallbacks {
    JNIEnv* env = nullptr;
    jclass cls = nullptr;
    jmethodID sessionCreated = nullptr, glReady = nullptr, glRelease = nullptr, sample = nullptr, drawEye = nullptr;
    jfloatArray view = nullptr, projection = nullptr;
    uint32_t exceptions = 0;

    bool resolve(JNIEnv* e, jclass c) {
        env = e;
        cls = c;
        sessionCreated = env->GetStaticMethodID(cls, "onSessionCreated", "()V");
        glReady = env->GetStaticMethodID(cls, "onGlReady", "()V");
        glRelease = env->GetStaticMethodID(cls, "onGlRelease", "()V");
        sample = env->GetStaticMethodID(cls, "onSample", "(JJJJFFFFFFF)V");
        drawEye = env->GetStaticMethodID(cls, "onDrawEye", "(I[F[FII)V");
        if (!sessionCreated || !glReady || !glRelease || !sample || !drawEye) { env->ExceptionClear(); return false; }
        view = static_cast<jfloatArray>(env->NewGlobalRef(env->NewFloatArray(16)));
        projection = static_cast<jfloatArray>(env->NewGlobalRef(env->NewFloatArray(16)));
        return view && projection;
    }
    void check() { if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); ++exceptions; } }
    void call(jmethodID m) { env->CallStaticVoidMethod(cls, m); check(); }
    void release() {
        if (view) env->DeleteGlobalRef(view);
        if (projection) env->DeleteGlobalRef(projection);
        view = projection = nullptr;
    }
};

std::string stringsJson(const std::vector<std::string>& values) {
    std::string out = "[";
    for (size_t i = 0; i < values.size(); ++i) out += (i ? "," : "") + xrspatial::jsonQuote(values[i]);
    return out + "]";
}

std::string operationsJson(const std::vector<xrspatial::Operation>& ops) {
    std::string o = "[";
    for (size_t i = 0; i < ops.size(); ++i)
        o += (i ? "," : "") + std::string("{\"operation\":") + xrspatial::jsonQuote(ops[i].name) + ",\"result\":" + std::to_string(int(ops[i].result)) + "}";
    return o + "]";
}

std::string runSession(JNIEnv* env, jclass nativeClass, jobject activity, jobject context, jobject surfaceObject,
                       const std::string& appName, float nearZ, float farZ) {
    std::lock_guard<std::mutex> lock(runMutex);
    auto stage = [](const std::string& name, const std::string& status, const std::string& failure, const std::string& extra = "") {
        return "\"" + name + "\":{\"status\":" + xrspatial::jsonQuote(status) + ",\"failure\":" + xrspatial::jsonQuote(failure) + extra + "}";
    };
    JavaCallbacks java;
    if (!java.resolve(env, nativeClass)) return "{" + stage("instance", "ERROR", "JavaCallbacksMissing") + "}";

    void* library = dlopen("libopenxr_loader.so", RTLD_NOW | RTLD_NOLOAD);
    xrspatial::LoaderFunctions fns;
    if (library) {
        fns.getInstanceProcAddr = reinterpret_cast<PFN_xrGetInstanceProcAddr>(dlsym(library, "xrGetInstanceProcAddr"));
        fns.enumerateExtensions = reinterpret_cast<PFN_xrEnumerateInstanceExtensionProperties>(dlsym(library, "xrEnumerateInstanceExtensionProperties"));
        fns.createInstance = reinterpret_cast<PFN_xrCreateInstance>(dlsym(library, "xrCreateInstance"));
        fns.destroyInstance = reinterpret_cast<PFN_xrDestroyInstance>(dlsym(library, "xrDestroyInstance"));
        fns.getInstanceProperties = reinterpret_cast<PFN_xrGetInstanceProperties>(dlsym(library, "xrGetInstanceProperties"));
        fns.getSystem = reinterpret_cast<PFN_xrGetSystem>(dlsym(library, "xrGetSystem"));
        fns.getSystemProperties = reinterpret_cast<PFN_xrGetSystemProperties>(dlsym(library, "xrGetSystemProperties"));
        fns.enumerateViewConfigurationViews = reinterpret_cast<PFN_xrEnumerateViewConfigurationViews>(dlsym(library, "xrEnumerateViewConfigurationViews"));
        fns.enumerateEnvironmentBlendModes = reinterpret_cast<PFN_xrEnumerateEnvironmentBlendModes>(dlsym(library, "xrEnumerateEnvironmentBlendModes"));
    }
    if (!library || !fns.complete()) { java.release(); return "{" + stage("instance", "ERROR", "VendorLoaderNotLoaded") + "}"; }

    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    jobject activityRef = env->NewGlobalRef(activity), contextRef = env->NewGlobalRef(context);
    std::string graphics = stage("graphics", "NOT_STARTED", ""), loop = "\"loop\":null";
    xrspatial::XrInstanceSetup setup(fns, {vm, contextRef, activityRef}, appName);
    const bool created = setup.create();
    if (created) {
        const XrInstance instance = setup.instance();
        auto getProc = setup.getInstanceProcAddr();
        auto graphicsError = [&](const std::string& why) { graphics = stage("graphics", "ERROR", why); };
        PFN_xrVoidFunction fn = nullptr;
        XrResult r = getProc(instance, "xrGetOpenGLESGraphicsRequirementsKHR", &fn);
        XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        if (r == XR_SUCCESS && fn) r = reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(fn)(instance, setup.system(), &requirements);
        Gles gl;
        PFN_xrVoidFunction createFn = nullptr, destroyFn = nullptr;
        if (r != XR_SUCCESS || !fn) graphicsError("xrGetOpenGLESGraphicsRequirementsKHR:" + std::to_string(int(r)));
        else if (!gl.create(int(XR_VERSION_MINOR(requirements.minApiVersionSupported)), surfaceObject ? ANativeWindow_fromSurface(env, surfaceObject) : nullptr))
            graphicsError(gl.failure);
        else if (getProc(instance, "xrCreateSession", &createFn) != XR_SUCCESS || getProc(instance, "xrDestroySession", &destroyFn) != XR_SUCCESS || !createFn || !destroyFn)
            graphicsError("LookupSessionFunctions");
        else {
            java.call(java.glReady);
            XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
            binding.display = gl.display;
            binding.config = gl.config;
            binding.context = gl.context;
            XrSessionCreateInfo sessionInfo{XR_TYPE_SESSION_CREATE_INFO};
            sessionInfo.next = &binding;
            sessionInfo.systemId = setup.system();
            XrSession session = XR_NULL_HANDLE;
            r = reinterpret_cast<PFN_xrCreateSession>(createFn)(instance, &sessionInfo, &session);
            if (r != XR_SUCCESS || session == XR_NULL_HANDLE) {
                graphicsError("xrCreateSession:" + std::to_string(int(r)));
            } else {
                // The runtime ignores RayNeo SDK focus reports made before a session exists.
                java.call(java.sessionCreated);
                xrspatial::FrameLoopLimits limits;
                limits.durationMs = std::numeric_limits<int64_t>::max() / 2;
                limits.maxFrames = std::numeric_limits<uint32_t>::max();
                limits.width = setup.info().viewWidth;
                limits.height = setup.info().viewHeight;
                xrspatial::FrameLoopIo io;
                io.cancelled = [] { return cancelled.load(); };
                io.monotonicNs = monotonicNs;
                io.idle = [] { std::this_thread::sleep_for(std::chrono::milliseconds(10)); };
                io.sample = [&](const xrspatial::LocatedPose& x) {
                    const auto& p = x.pose.position;
                    const auto& q = x.pose.orientation;
                    env->CallStaticVoidMethod(java.cls, java.sample, jlong(x.index), jlong(x.xrTime), jlong(x.hostReceiptNs), jlong(x.flags),
                                              p.x, p.y, p.z, q.x, q.y, q.z, q.w);
                    java.check();
                };
                std::array<xrspatial::RenderMat4, 2> views{}, projections{};
                bool drawable = false;
                io.frameViews = [&](bool located, const XrSpaceLocation& head, const std::array<XrView, 2>& eyes) {
                    drawable = located && xrspatial::renderPoseUsable(head.pose);
                    if (!drawable) return;
                    for (unsigned eye = 0; eye < 2; ++eye) {
                        views[eye] = xrspatial::renderEyeView(head.pose, eyes[eye].pose);
                        projections[eye] = xrspatial::renderProjection(xrspatial::renderFov(eyes[eye].fov), nearZ, farZ);
                    }
                };
                std::vector<xrspatial::HudRect> hud;
                io.drawEye = [&](unsigned eye, uint32_t image, uint32_t w, uint32_t h, uint64_t) {
                    if (!gl.begin(eye, image, w, h)) return false;
                    if (drawable) {
                        env->SetFloatArrayRegion(java.view, 0, 16, views[eye].m.data());
                        env->SetFloatArrayRegion(java.projection, 0, 16, projections[eye].m.data());
                        env->CallStaticVoidMethod(java.cls, java.drawEye, jint(eye), java.view, java.projection, jint(w), jint(h));
                        java.check();
                    }
                    if (eye == 0) {
                        std::lock_guard<std::mutex> hudLock(hudMutex);
                        hud = xrspatial::hudLayout(hudTop, hudBottom, hudCentre, int(w), int(h));
                    }
                    return gl.end(hud);
                };
                xrspatial::FrameLoop frameLoop(getProc, instance, session, setup.info().blendMode, limits, io);
                loop = "\"loop\":" + frameLoop.execute().toJson();
                const XrResult destroyed = reinterpret_cast<PFN_xrDestroySession>(destroyFn)(session);
                graphics = stage("graphics", destroyed == XR_SUCCESS ? "COMPLETED" : "ERROR", destroyed == XR_SUCCESS ? "" : "xrDestroySession:" + std::to_string(int(destroyed)),
                                 ",\"surface\":" + xrspatial::jsonQuote(gl.surfaceText) + ",\"gles\":" + xrspatial::jsonQuote(gl.version) +
                                 ",\"viewWidth\":" + std::to_string(limits.width) + ",\"viewHeight\":" + std::to_string(limits.height) +
                                 ",\"callbackExceptions\":" + std::to_string(java.exceptions));
            }
            java.call(java.glRelease);
        }
        gl.destroy();
    }
    setup.destroy();
    const auto& info = setup.info();
    std::string failure = info.failure;
    if (!failure.empty() && !info.operations.empty() && info.operations.back().name == failure)
        failure += ":" + std::to_string(int(info.operations.back().result));
    const std::string instanceJson = stage("instance", created ? "COMPLETED" : "ERROR", failure,
        ",\"runtimeName\":" + xrspatial::jsonQuote(info.runtimeName) + ",\"runtimeVersion\":" + xrspatial::jsonQuote(info.runtimeVersion) +
        ",\"systemName\":" + xrspatial::jsonQuote(info.systemName) + ",\"extensions\":" + stringsJson(info.extensions) +
        ",\"operations\":" + operationsJson(info.operations));
    env->DeleteGlobalRef(activityRef);
    env->DeleteGlobalRef(contextRef);
    java.release();
    __android_log_print(ANDROID_LOG_INFO, kTag, "session ended: %s", failure.empty() ? "ok" : failure.c_str());
    return "{" + instanceJson + "," + graphics + "," + loop + "}";
}

std::string javaString(JNIEnv* env, jstring text) {
    if (!text) return {};
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (!chars) return {};
    std::string value(chars);
    env->ReleaseStringUTFChars(text, chars);
    return value;
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_alexskuznetsov_xrspatial_XrSpatialNative_run(JNIEnv* env, jclass cls, jobject activity, jobject context, jobject surface,
                                                           jstring appName, jfloat nearZ, jfloat farZ) {
    const std::string result = runSession(env, cls, activity, context, surface, javaString(env, appName), nearZ, farZ);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_alexskuznetsov_xrspatial_XrSpatialNative_cancel(JNIEnv*, jclass) { cancelled.store(true); }

extern "C" JNIEXPORT void JNICALL
Java_io_github_alexskuznetsov_xrspatial_XrSpatialNative_resetCancel(JNIEnv*, jclass) { cancelled.store(false); }

extern "C" JNIEXPORT void JNICALL
Java_io_github_alexskuznetsov_xrspatial_XrSpatialNative_setHud(JNIEnv* env, jclass, jstring top, jstring bottom, jstring centre) {
    std::lock_guard<std::mutex> lock(hudMutex);
    hudTop = javaString(env, top);
    hudBottom = javaString(env, bottom);
    hudCentre = javaString(env, centre);
}
