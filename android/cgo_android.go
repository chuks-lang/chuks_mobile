package main

// Link the Android host: the JNI bridge (jni.cpp) + the prebuilt Yoga archive.
// SRCDIR is the generated build dir where build.sh stages yoga/ next to the sources.

// #cgo CPPFLAGS: -I${SRCDIR}/yoga/include
// #cgo CXXFLAGS: -std=c++20 -fno-exceptions -fno-rtti
// #cgo LDFLAGS: ${SRCDIR}/yoga/libyoga.a -lc++_static -lc++abi -llog
import "C"
