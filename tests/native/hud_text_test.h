#pragma once
// HOST ONLY checks of the head-locked debug text layout (hud_text.h).
#include "hud_text.h"
#include <functional>

namespace hud_text_tests {
using namespace xrspatial;
inline bool inside(const std::vector<HudRect>& rects, int w, int h) {
    for (const auto& r : rects) if (r.x < 0 || r.y < 0 || r.x + r.w > w || r.y + r.h > h || r.w <= 0 || r.h <= 0) return false;
    return true;
}

inline void run(const std::function<void(bool, const char*)>& expect, const std::function<void(const char*)>& pass) {
    for (char c : std::string("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789./<>:-")) expect(hudGlyph(c) != nullptr, "glyph for every documented character");
    expect(hudGlyph(' ') == nullptr && hudGlyph('+') == nullptr, "space and unknown characters draw nothing");
    expect(hudNormalize("x 0.25 z+1") == "X 0.25 Z 1", "lower case upper-cased, unknown characters become spaces");
    pass("hud_text_glyphs_and_normalize");

    std::vector<HudRect> one;
    hudText(one, "-", 0, 70, 10);
    expect(one.size() == 1 && one[0].w == 50 && one[0].h == 10 && one[0].y == 30, "glyph rows merged into runs");
    const auto all = hudLayout("XR SPATIAL CUBES", "X 0.12  Y -0.01  Z -1.50", "WAITING FOR TRACKING", 640, 480);
    expect(!all.empty() && inside(all, 640, 480), "top, bottom and centre lines inside the 640x480 eye image");
    expect(inside(hudLayout("", std::string(100, 'W'), "", 640, 480), 640, 480), "a 100-character line shrinks to fit 640 px");
    expect(hudLayout("", "", "", 640, 480).empty(), "no text, no rectangles");
    bool centreFree = true;  // top/bottom lines keep the middle of the view free for content
    for (const auto& r : hudLayout("XR SPATIAL CUBES", "X 0.12", "", 640, 480)) centreFree = centreFree && (r.y + r.h < 140 || r.y > 400);
    expect(centreFree, "top and bottom lines keep the centre free");
    pass("hud_text_layout_bounds");
}
}  // namespace hud_text_tests
