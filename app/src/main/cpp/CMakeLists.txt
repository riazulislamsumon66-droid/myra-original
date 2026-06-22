cmake_minimum_required(VERSION 3.22.1)

project("live2d_native_bridge")

# Live2D Core Library (only when .so is available)
# When libCubismCore.so is added to jniLibs, uncomment below:
# add_library(cubism_core SHARED IMPORTED)
# set_target_properties(cubism_core PROPERTIES
#     IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libCubismCore.so"
#     IMPORTED_NO_SONAME TRUE
# )

# JNI Bridge
add_library(live2d_bridge SHARED jni_bridge.cpp)

# Find Android libraries
find_library(log-lib log)

# Link
target_link_libraries(live2d_bridge
    ${log-lib}
)

# If cubism_core is available, link it:
# target_link_libraries(live2d_bridge cubism_core)
