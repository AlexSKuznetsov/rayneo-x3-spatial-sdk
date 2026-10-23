cmake_minimum_required(VERSION 3.22.1)
project(xrspatial LANGUAGES CXX)
get_filename_component(SDK_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
add_library(xrspatial SHARED xrspatial_jni.cpp)
target_include_directories(xrspatial PRIVATE "${SDK_ROOT}/third_party/openxr/include")
target_compile_features(xrspatial PRIVATE cxx_std_17)
target_compile_definitions(xrspatial PRIVATE XR_USE_PLATFORM_ANDROID XR_USE_GRAPHICS_API_OPENGL_ES XR_NO_PROTOTYPES)
target_compile_options(xrspatial PRIVATE -Wall -Wextra -Wpedantic -Werror
    -Wno-missing-field-initializers)  # OpenXR structs are initialised as {XR_TYPE_...}; the rest is zero
target_link_options(xrspatial PRIVATE -Wl,--no-undefined)
# No OpenXR loader is linked or packaged: the RayNeo SDK loads the vendor loader at run time and the
# library resolves the standard entry points from it with dlsym.
target_link_libraries(xrspatial PRIVATE android log dl EGL GLESv3)
