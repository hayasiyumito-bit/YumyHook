cmake_minimum_required(VERSION 3.22.1)
project("yumyhook_native")

find_package(shadowhook REQUIRED CONFIG)

add_library(yumyhook_native SHARED native_bridge.cpp)

target_compile_features(yumyhook_native PRIVATE cxx_std_17)

target_link_libraries(
    yumyhook_native
    shadowhook::shadowhook
    log
)
