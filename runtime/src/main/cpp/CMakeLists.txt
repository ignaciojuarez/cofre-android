cmake_minimum_required(VERSION 3.22)
project(cofre C)

# libcofre.so: Vulkan check (launcher), JVM launch + window + input queue (:game, shared by ART and HotSpot).
add_library(cofre SHARED cofre.c vulkan_check.c)
target_compile_options(cofre PRIVATE -std=c17 -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(cofre PRIVATE android log dl)
target_link_options(cofre PRIVATE -Wl,-z,max-page-size=16384)
