#pragma once
// Small shared helpers: OpenXR result names, JSON quoting, operation records.
#include <openxr/openxr.h>
#include <openxr/openxr_reflection.h>
#include <cstdint>
#include <string>

namespace xrspatial {
// One OpenXR call with its raw result; failures are reported with these, never guessed.
struct Operation {
    std::string name;
    XrResult result;
    std::string resultName;
};

inline std::string resultName(XrResult result) {
    struct NamedResult { int64_t value; const char* name; };
#define XRSPATIAL_RESULT_ENTRY(name, value) {value, #name},
    static constexpr NamedResult names[] = {XR_LIST_ENUM_XrResult(XRSPATIAL_RESULT_ENTRY)};
#undef XRSPATIAL_RESULT_ENTRY
    for (const auto& item : names) if (item.value == result) return item.name;
    return "XR_RESULT_UNKNOWN(" + std::to_string(static_cast<int64_t>(result)) + ")";
}

inline std::string jsonQuote(const std::string& text) {
    static constexpr char hex[] = "0123456789abcdef";
    std::string out = "\"";
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') { out += '\\'; out += static_cast<char>(c); }
        else if (c < 0x20) { out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15]; }
        else out += static_cast<char>(c);  // UTF-8 kept as is
    }
    return out + '"';
}
}  // namespace xrspatial
