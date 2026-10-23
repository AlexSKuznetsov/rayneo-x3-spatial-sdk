#pragma once
// Per-eye view and projection matrices for world-locked rendering (host-testable, no GL here).
// clip = projection(eye fov) * inverse(head-in-LOCAL * eye-in-VIEW) * point-in-LOCAL, using this frame's
// xrLocateSpace(VIEW, LOCAL) head pose and xrLocateViews(VIEW) eye poses/fovs. On the X3 Pro the eye views
// are pitched about 10.8 deg down; that pitch is physical (verified with an eye-height wall mark).
#include <openxr/openxr.h>
#include <array>
#include <cmath>

namespace xrspatial {
struct RenderMat4 { std::array<float, 16> m{}; };  // column-major, m[column * 4 + row] (GL layout)

inline RenderMat4 renderMultiply(const RenderMat4& a, const RenderMat4& b) {
    RenderMat4 r;
    for (int c = 0; c < 4; ++c)
        for (int row = 0; row < 4; ++row) {
            float sum = 0;
            for (int k = 0; k < 4; ++k) sum += a.m[k * 4 + row] * b.m[c * 4 + k];
            r.m[c * 4 + row] = sum;
        }
    return r;
}

// Rigid transform child-to-parent of an OpenXR pose (assumes a unit quaternion; see renderPoseUsable).
inline RenderMat4 renderPoseMatrix(const XrPosef& p) {
    const float x = p.orientation.x, y = p.orientation.y, z = p.orientation.z, w = p.orientation.w;
    RenderMat4 r;
    r.m = {1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
           2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
           2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
           p.position.x, p.position.y, p.position.z, 1};
    return r;
}

inline RenderMat4 renderInverseRigid(const RenderMat4& a) {
    RenderMat4 r;
    for (int c = 0; c < 3; ++c)
        for (int row = 0; row < 3; ++row) r.m[c * 4 + row] = a.m[row * 4 + c];
    for (int row = 0; row < 3; ++row)
        r.m[12 + row] = -(r.m[row] * a.m[12] + r.m[4 + row] * a.m[13] + r.m[8 + row] * a.m[14]);
    r.m[15] = 1;
    return r;
}

// Same matrix as the OpenXR SDK's XrMatrix4x4f_CreateProjectionFov for OpenGL ES (clip z in [-w, w]).
inline RenderMat4 renderProjection(const XrFovf& fov, float nearZ, float farZ) {
    const float l = std::tan(fov.angleLeft), r = std::tan(fov.angleRight);
    const float u = std::tan(fov.angleUp), d = std::tan(fov.angleDown);
    const float w = r - l, h = u - d;
    RenderMat4 p;
    p.m = {2 / w, 0, 0, 0,
           0, 2 / h, 0, 0,
           (r + l) / w, (u + d) / h, -(farZ + nearZ) / (farZ - nearZ), -1,
           0, 0, -2 * farZ * nearZ / (farZ - nearZ), 0};
    return p;
}

// Same rule as the SDK WarmupGate: all components finite, quaternion norm within 1e-3 of 1.
inline bool renderPoseUsable(const XrPosef& p) {
    const float v[] = {p.position.x, p.position.y, p.position.z, p.orientation.x, p.orientation.y, p.orientation.z, p.orientation.w};
    for (float x : v) if (!std::isfinite(x)) return false;
    const double n = std::sqrt(double(v[3]) * v[3] + double(v[4]) * v[4] + double(v[5]) * v[5] + double(v[6]) * v[6]);
    return std::fabs(n - 1.0) <= 1e-3;
}

// The X3 Pro runtime reports angleLeft and angleDown as positive magnitudes (left 0.2007, right 0.2229,
// up 0.1556, down 0.1620 rad), against the OpenXR sign convention (left/down negative for a view that
// contains its own axis). Used raw, a projection stretches x about 86x. Render-only interpretation: when
// both horizontal (or both vertical) angles are positive, the left (down) one is negated. The submitted
// layer keeps the raw values.
inline XrFovf renderFov(const XrFovf& raw, bool* reinterpreted = nullptr) {
    XrFovf fov = raw;
    if (fov.angleLeft > 0 && fov.angleRight > 0) fov.angleLeft = -fov.angleLeft;
    if (fov.angleUp > 0 && fov.angleDown > 0) fov.angleDown = -fov.angleDown;
    if (reinterpreted) *reinterpreted = fov.angleLeft != raw.angleLeft || fov.angleDown != raw.angleDown;
    return fov;
}

// View matrix of one eye: inverse(head-in-LOCAL * eye-in-VIEW). Column-major, LOCAL -> eye space.
inline RenderMat4 renderEyeView(const XrPosef& headInLocal, const XrPosef& eyeInView) {
    return renderInverseRigid(renderMultiply(renderPoseMatrix(headInLocal), renderPoseMatrix(eyeInView)));
}

inline std::array<float, 4> renderTransform(const RenderMat4& a, float x, float y, float z) {
    std::array<float, 4> out{};
    for (int row = 0; row < 4; ++row) out[row] = a.m[row] * x + a.m[4 + row] * y + a.m[8 + row] * z + a.m[12 + row];
    return out;
}
}  // namespace xrspatial
