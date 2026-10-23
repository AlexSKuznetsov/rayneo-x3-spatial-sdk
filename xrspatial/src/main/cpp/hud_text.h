#pragma once
// Head-locked debug text (HUD) drawn by the SDK over the app's frame: a 5x7 bitmap font as scissor-clear
// rectangles (Latin capitals, digits, . : / - < >). The display is additive: black is transparent.
#include <cstdint>
#include <string>
#include <vector>

namespace xrspatial {
struct HudRect { int x, y, w, h; };  // GL window coordinates, origin bottom-left

inline const uint8_t* hudGlyph(char c) {
    struct Glyph { char c; uint8_t rows[7]; };
    static const Glyph font[] = {
        {'A', {0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001}},
        {'B', {0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110}},
        {'C', {0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110}},
        {'D', {0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110}},
        {'E', {0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111}},
        {'F', {0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000}},
        {'G', {0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111}},
        {'H', {0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001}},
        {'I', {0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110}},
        {'J', {0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100}},
        {'K', {0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001}},
        {'L', {0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111}},
        {'M', {0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001}},
        {'N', {0b10001, 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001}},
        {'O', {0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110}},
        {'P', {0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000}},
        {'Q', {0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101}},
        {'R', {0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001}},
        {'S', {0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110}},
        {'T', {0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100}},
        {'U', {0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110}},
        {'V', {0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100}},
        {'W', {0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b10101, 0b01010}},
        {'X', {0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001}},
        {'Y', {0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100}},
        {'Z', {0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111}},
        {'0', {0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110}},
        {'1', {0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110}},
        {'2', {0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111}},
        {'3', {0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110}},
        {'4', {0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010}},
        {'5', {0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110}},
        {'6', {0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110}},
        {'7', {0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000}},
        {'8', {0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110}},
        {'9', {0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100}},
        {'.', {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100}},
        {':', {0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b00000}},
        {'/', {0b00001, 0b00010, 0b00010, 0b00100, 0b01000, 0b01000, 0b10000}},
        {'-', {0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000}},
        {'>', {0b01000, 0b00100, 0b00010, 0b00001, 0b00010, 0b00100, 0b01000}},
        {'<', {0b00010, 0b00100, 0b01000, 0b10000, 0b01000, 0b00100, 0b00010}},
    };
    for (const auto& g : font) if (g.c == c) return g.rows;
    return nullptr;  // space and anything unknown draw nothing
}

inline int hudTextWidth(const std::string& text, int scale) {
    return text.empty() ? 0 : int(text.size()) * 6 * scale - scale;
}

// Rectangles for one line of text; consecutive lit pixels of a glyph row are merged into one rect.
inline void hudText(std::vector<HudRect>& out, const std::string& text, int x, int top, int scale) {
    for (size_t n = 0; n < text.size(); ++n) {
        const uint8_t* rows = hudGlyph(text[n]);
        if (!rows) continue;
        const int left = x + int(n) * 6 * scale;
        for (int r = 0; r < 7; ++r) {
            for (int c = 0; c < 5;) {
                if (!(rows[r] & (0x10 >> c))) { ++c; continue; }
                int end = c;
                while (end < 5 && (rows[r] & (0x10 >> end))) ++end;
                out.push_back({left + c * scale, top - (r + 1) * scale, (end - c) * scale, scale});
                c = end;
            }
        }
    }
}

inline void hudCentered(std::vector<HudRect>& out, const std::string& text, int width, int top, int scale) {
    hudText(out, text, (width - hudTextWidth(text, scale)) / 2, top, scale);
}

// Upper-case and drop characters the font does not have (shown as spaces).
inline std::string hudNormalize(const std::string& text) {
    std::string out;
    for (char c : text) {
        const char u = (c >= 'a' && c <= 'z') ? char(c - 'a' + 'A') : c;
        out += hudGlyph(u) ? u : ' ';
    }
    return out;
}

// Layout: one line at the top, one at the bottom, optional centre line; long lines shrink to fit.
inline std::vector<HudRect> hudLayout(const std::string& top, const std::string& bottom, const std::string& centre, int width, int height) {
    std::vector<HudRect> rects;
    auto line = [&](const std::string& raw, int y, int scale) {
        const std::string text = hudNormalize(raw);
        while (scale > 1 && hudTextWidth(text, scale) > width - 20) --scale;
        hudCentered(rects, text, width, y, scale);
    };
    if (!top.empty()) line(top, height - 20, 3);
    if (!bottom.empty()) line(bottom, 60, 4);
    if (!centre.empty()) line(centre, height / 2 + 21, 6);
    return rects;
}
}  // namespace xrspatial
