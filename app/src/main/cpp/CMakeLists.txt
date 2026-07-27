cmake_minimum_required(VERSION 3.10.2)
project(nukacast_airplay)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 11)

add_subdirectory(legacy-airplay/lib/crypto)
add_subdirectory(legacy-airplay/lib/curve25519)
add_subdirectory(legacy-airplay/lib/ed25519)
add_subdirectory(legacy-airplay/lib/playfair)
add_subdirectory(legacy-airplay/lib/plist)
add_subdirectory(legacy-airplay/lib/fdk-aac)
add_subdirectory(legacy-airplay/lib)

add_library(nukacast_airplay SHARED nukacast_airplay.cpp)
target_include_directories(nukacast_airplay PRIVATE legacy-airplay/lib)

find_library(log_lib log)
target_link_libraries(nukacast_airplay play-lib ${log_lib})
