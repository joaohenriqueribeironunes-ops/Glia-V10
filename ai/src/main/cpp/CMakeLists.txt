cmake_minimum_required(VERSION 3.22.1)
project(glia_llm CXX)

# llama.cpp compilado para Android fica em ai/src/main/jniLibs/<abi>/.
# Os .so são empacotados pelo AGP (jniLibs) e linkados aqui por caminho.
set(LLAMA_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(LLAMA_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama)
set(GGML_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama)

add_library(glia_llm SHARED glia_bridge.cpp)

target_compile_features(glia_llm PRIVATE cxx_std_17)
target_compile_options(glia_llm PRIVATE -O3 -fno-exceptions -fno-rtti -Wall -Wno-unused-parameter)
target_include_directories(glia_llm PRIVATE ${LLAMA_INCLUDE_DIR} ${GGML_INCLUDE_DIR})

target_link_libraries(glia_llm
        ${LLAMA_LIB_DIR}/libllama.so
        ${LLAMA_LIB_DIR}/libggml.so
        ${LLAMA_LIB_DIR}/libggml-base.so
        ${LLAMA_LIB_DIR}/libggml-cpu.so
        log android)
