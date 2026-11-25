cmake_minimum_required(VERSION 3.22.1)
project(glia_llm CXX)

add_library(glia_llm SHARED
        glia_core.cpp
        glia_jni.cpp)

target_compile_features(glia_llm PRIVATE cxx_std_17)
target_compile_options(glia_llm PRIVATE -O3 -ffast-math -funroll-loops -Wall -Wno-unused-parameter)
target_link_libraries(glia_llm log android)
