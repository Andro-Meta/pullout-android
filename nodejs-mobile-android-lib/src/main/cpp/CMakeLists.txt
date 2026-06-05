cmake_minimum_required(VERSION 3.18.1)
project(nodejs-mobile-android)

add_library(nodejs-mobile-native SHARED
    node_bridge.cpp
)

include_directories(
    ${CMAKE_SOURCE_DIR}/../../../libnode/include/node/
)

add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../../../libnode/bin/${ANDROID_ABI}/libnode.so
)

find_library(log-lib log)

target_link_libraries(nodejs-mobile-native
    libnode
    ${log-lib}
)
