#pragma once
// HOST ONLY checks of the per-eye view/projection maths (render_math.h).
#include "render_math.h"
#include <cmath>
#include <functional>

namespace render_math_tests {
using namespace xrspatial;
inline XrPosef pose(float px, float py, float pz, float qx = 0, float qy = 0, float qz = 0, float qw = 1) {
    XrPosef p{};
    p.position = {px, py, pz};
    p.orientation = {qx, qy, qz, qw};
    return p;
}
inline bool near(float a, float b, float tolerance = 1e-4f) { return std::fabs(a - b) <= tolerance; }
struct Ndc { float x, y, w; };
// Normalised device x/y of a LOCAL point for a head pose, an eye pose in VIEW and an eye fov.
inline Ndc ndc(const XrPosef& head, const XrPosef& eye, const XrFovf& fov, float x, float y, float z) {
    const auto clip = renderTransform(renderMultiply(renderProjection(renderFov(fov), 0.05f, 100.f), renderEyeView(head, eye)), x, y, z);
    return {clip[0] / clip[3], clip[1] / clip[3], clip[3]};
}

inline void run(const std::function<void(bool, const char*)>& expect, const std::function<void(const char*)>& pass) {
    const XrFovf fov{-0.4f, 0.4f, 0.3f, -0.3f};
    const XrPosef centre = pose(0, 0, 0);
    {
        const auto a = ndc(centre, centre, fov, 0, -0.2f, -1.5f);
        expect(a.w > 0 && near(a.x, 0) && near(a.y, -0.2f / 1.5f / std::tan(0.3f)), "point ahead and below lands below the centre");
        const auto right = ndc(pose(0.5f, 0, 0), centre, fov, 0, 0, -1.5f);
        expect(right.w > 0 && near(right.x, -0.5f / 1.5f / std::tan(0.4f)), "head 0.5 m right: point moves left by the exact parallax");
        const auto closer = ndc(pose(0, 0, -0.5f), centre, fov, 0.1f, 0, -1.5f);
        const auto start = ndc(centre, centre, fov, 0.1f, 0, -1.5f);
        expect(near(closer.x / start.x, 1.5f), "head 0.5 m forward: offsets grow by 1.5 m / 1.0 m");
        const auto squat = ndc(pose(0, -0.3f, 0), centre, fov, 0, -0.2f, -1.5f);
        expect(near(squat.y, 0.1f / 1.5f / std::tan(0.3f)), "head 0.3 m lower: point moves up");
        pass("render_math_translation_parallax");
    }
    {
        const float s = std::sin(0.3926991f), c = std::cos(0.3926991f);  // 45 deg yaw left about +Y
        const auto turned = ndc(pose(0, 0, 0, 0, s, 0, c), centre, fov, 0, 0, -1.5f);
        expect(turned.w > 0 && near(turned.x, 1.f / std::tan(0.4f), 1e-3f), "head 45 deg left: point leaves the view to the right");
        pass("render_math_rotation");
    }
    {
        const auto left = ndc(centre, pose(-0.03f, 0, 0), fov, 0, 0, -1.5f);
        const auto right = ndc(centre, pose(0.03f, 0, 0), fov, 0, 0, -1.5f);
        expect(left.x > 0 && right.x < 0 && near(left.x, -right.x), "stereo disparity from the eye poses");
        const XrFovf asym{-0.5f, 0.3f, 0.4f, -0.2f};
        expect(near(ndc(centre, centre, asym, std::tan(-0.5f), 0, -1).x, -1) && near(ndc(centre, centre, asym, 0, std::tan(0.4f), -1).y, 1),
               "asymmetric fov edges map to NDC -1 / +1");
        const auto p = renderProjection(asym, 0.05f, 100.f);
        const auto nearClip = renderTransform(p, 0, 0, -0.05f), farClip = renderTransform(p, 0, 0, -100.f);
        expect(near(nearClip[2] / nearClip[3], -1) && near(farClip[2] / farClip[3], 1, 1e-3f), "GL clip depth range [-1, 1]");
        pass("render_math_projection_and_stereo");
    }
    {
        const float n = std::sqrt(0.1f * 0.1f + 0.7f * 0.7f + 0.2f * 0.2f + 0.5f * 0.5f);
        const auto p = pose(0.3f, -0.1f, 0.7f, 0.1f / n, 0.7f / n, -0.2f / n, 0.5f / n);
        const auto id = renderMultiply(renderInverseRigid(renderPoseMatrix(p)), renderPoseMatrix(p));
        bool identity = true;
        for (int i = 0; i < 16; ++i) identity = identity && near(id.m[i], (i % 5 == 0) ? 1.f : 0.f);
        expect(identity, "inverse rigid transform");
        expect(renderPoseUsable(p) && renderPoseUsable(centre), "unit poses usable");
        expect(!renderPoseUsable(pose(std::nanf(""), 0, 0)) && !renderPoseUsable(pose(0, 0, 0, 0, 0, 0, 0)) &&
               !renderPoseUsable(pose(0, 0, 0, 0, 0, 0, 1.002f)) && !renderPoseUsable(pose(0, 0, 0, 0, 0, 0, INFINITY)),
               "non-finite, zero and non-unit quaternions are not drawn (start-up garbage)");
        pass("render_math_pose_matrix_and_usable");
    }
    {
        // Eye fov as returned by the X3 Pro runtime: positive left/down magnitudes; eyes pitched ~10.8 deg down.
        const XrFovf raw{0.2007f, 0.2229f, 0.1556f, 0.1620f};
        bool changed = false;
        const auto fixedFov = renderFov(raw, &changed);
        expect(changed && fixedFov.angleLeft == -0.2007f && fixedFov.angleRight == 0.2229f && fixedFov.angleUp == 0.1556f &&
               fixedFov.angleDown == -0.1620f, "device fov: left/down magnitudes taken as negative for rendering");
        bool specChanged = true;
        const auto same = renderFov(fov, &specChanged);
        expect(!specChanged && same.angleLeft == fov.angleLeft && same.angleDown == fov.angleDown, "spec-convention fov unchanged");
        XrPosef eye = pose(-0.0325f, 0, 0, -0.0938f, 0, 0, 0.99559f);
        const auto cube = ndc(centre, eye, raw, 0, -0.2f, -1.5f);
        const auto horizon = ndc(centre, eye, raw, 0.3f, -0.01f, -2.f);
        expect(cube.w > 0 && std::fabs(cube.x) < 1 && std::fabs(cube.y) < 1, "with the device views a point 1.5 m ahead, 0.2 m low is on screen");
        expect(horizon.w > 0 && horizon.y > 1.f, "the start eye height is just above the pitched-down display");
        pass("render_math_device_fov_convention");
    }
}
}  // namespace render_math_tests
