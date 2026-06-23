cmake_minimum_required(VERSION 3.22.1)

project("live2d_native_bridge")

# Only build for ABIs that have the native library
set(AVAILABLE_ABIS "")
foreach(abi arm64-v8a x86 x86_64)
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${abi}/libLive2DCubismCore.so")
        list(APPEND AVAILABLE_ABIS ${abi})
    endif()
endforeach()

if(NOT AVAILABLE_ABIS)
    message(FATAL_ERROR "No libLive2DCubismCore.so found in jniLibs!")
endif()

message(STATUS "Building Live2D bridge for ABIs: ${AVAILABLE_ABIS}")

# Live2D Core Library
add_library(cubism_core SHARED IMPORTED)
set_target_properties(cubism_core PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libLive2DCubismCore.so"
    IMPORTED_NO_SONAME TRUE
)

# JNI Bridge
add_library(live2d_bridge SHARED jni_bridge.cpp)

# Find Android libraries
find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)
find_library(jnigraphics-lib jnigraphics)

# Include Live2D headers
target_include_directories(live2d_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Link everything
target_link_libraries(live2d_bridge
    cubism_core
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${glesv2-lib}
    ${jnigraphics-lib}
)
