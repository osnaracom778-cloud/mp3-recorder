cmake_minimum_required(VERSION 3.22.1)
project("mp3lamejni" C CXX)

set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lame)

add_library(mp3lame STATIC
        ${LAME_DIR}/bitstream.c
        ${LAME_DIR}/encoder.c
        ${LAME_DIR}/fft.c
        ${LAME_DIR}/gain_analysis.c
        ${LAME_DIR}/id3tag.c
        ${LAME_DIR}/lame.c
        ${LAME_DIR}/newmdct.c
        ${LAME_DIR}/presets.c
        ${LAME_DIR}/psymodel.c
        ${LAME_DIR}/quantize.c
        ${LAME_DIR}/quantize_pvt.c
        ${LAME_DIR}/reservoir.c
        ${LAME_DIR}/set_get.c
        ${LAME_DIR}/tables.c
        ${LAME_DIR}/takehiro.c
        ${LAME_DIR}/util.c
        ${LAME_DIR}/vbrquantize.c
        ${LAME_DIR}/VbrTag.c
        ${LAME_DIR}/version.c)

target_include_directories(mp3lame PUBLIC ${LAME_DIR})
# ieee754_float32_t는 glibc <ieee754.h> 전용 타입 — Android bionic에는 없으므로 float으로 정의
target_compile_definitions(mp3lame PRIVATE STDC_HEADERS=1 HAVE_STDINT_H=1
        ieee754_float32_t=float)
# LAME sources trip these warnings on modern clang; they are harmless
target_compile_options(mp3lame PRIVATE -O3 -fno-strict-aliasing
        -Wno-unused-but-set-variable -Wno-unused-variable -Wno-absolute-value)

add_library(lamejni SHARED lame-jni.cpp)
target_link_libraries(lamejni mp3lame log)
