# luau-jni's native library: Luau's compiler, VM and native code generator, and the JNI bridge (luau_jni.cpp), as one
# shared library (luaujni) with nothing else to load beside it: Luau linked in statically, and the C/C++ runtime too.
cmake_minimum_required(VERSION 3.22)
project(luaujni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")         # (/MT: no Visual C++ runtime to install)

# Luau: luau-lang/luau "Sync to upstream/release/732", the version luau-java 1.2.1 is built from.
include(FetchContent)
set(LUAU_BUILD_CLI OFF CACHE BOOL "" FORCE)
set(LUAU_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LUAU_STATIC_CRT ON CACHE BOOL "" FORCE)
FetchContent_Declare(luau
        URL https://github.com/luau-lang/luau/archive/decb2d0526797a175d7c5ba8d4d78858ced98553.tar.gz
        URL_HASH SHA256=85a31d9937b64fc45ac6ba7c02d9d74f382ab97fb882e83526aa54c5ab4f2518)
FetchContent_MakeAvailable(luau)

# JNI's headers: the JDK's at JAVA_HOME (the Gradle build passes its own).
set(JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "The JDK whose JNI headers to build with")
if (NOT EXISTS "${JAVA_HOME}/include/jni.h")
    message(FATAL_ERROR "No JDK at JAVA_HOME (${JAVA_HOME}): set it, or -DJAVA_HOME=")
endif ()
if (WIN32)
    set(JNI_PLATFORM win32)
elseif (APPLE)
    set(JNI_PLATFORM darwin)
else ()
    set(JNI_PLATFORM linux)
endif ()

add_library(luaujni SHARED luau_jni.cpp)
target_include_directories(luaujni PRIVATE "${JAVA_HOME}/include" "${JAVA_HOME}/include/${JNI_PLATFORM}")
# (Luau.VM.Internals: the VM's own headers, for luaD_pcall: luau_jni.cpp's protected calls.)
target_link_libraries(luaujni PRIVATE Luau.Compiler Luau.CodeGen Luau.VM Luau.VM.Internals)
# One folder for it whatever the generator ($<1:...>: no Release/ or Debug/ under it).
set_target_properties(luaujni PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}/lib>"
        LIBRARY_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}/lib>")
if (CMAKE_SYSTEM_NAME STREQUAL "Linux")
    # (Only JNI's entry points exported, and libstdc++ linked in: one .so for any distribution of the build's age or newer.)
    set_target_properties(luaujni PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
    target_link_options(luaujni PRIVATE -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL)
elseif (APPLE)
    set_target_properties(luaujni PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
endif ()
