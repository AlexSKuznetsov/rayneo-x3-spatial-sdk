// Host tests of the native headers of :xrspatial against fake OpenXR entry points (no device, no GL).
// Build and run: python tools/run_native_tests.py
#include "frame_loop_test.h"
#include "hud_text_test.h"
#include "render_math_test.h"
#include "xr_instance_test.h"
#include <iostream>
#include <stdexcept>
#include <utility>

using Suite = void (*)(const std::function<void(bool, const char*)>&, const std::function<void(const char*)>&);

int main() {
    unsigned passed = 0, failed = 0;
    auto expect = [](bool condition, const char* message) { if (!condition) throw std::runtime_error(message); };
    auto pass = [&](const char* name) { ++passed; std::cout << "PASS " << name << '\n'; };
    const std::pair<const char*, Suite> suites[] = {
        {"xr_instance", xr_instance_tests::run}, {"frame_loop", frame_loop_tests::run},
        {"render_math", render_math_tests::run}, {"hud_text", hud_text_tests::run}};
    for (const auto& [name, run] : suites) {
        try {
            run(expect, pass);
        } catch (const std::exception& e) {
            ++failed;
            std::cout << "FAIL " << name << ": " << e.what() << '\n';
        }
    }
    if (failed) std::cout << "NATIVE_TESTS_FAILED=" << failed << '\n';
    else std::cout << "NATIVE_TESTS_PASSED=" << passed << '\n';
    return failed ? 1 : 0;
}
