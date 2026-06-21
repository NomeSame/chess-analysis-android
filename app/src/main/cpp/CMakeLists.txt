cmake_minimum_required(VERSION 3.22.1)
project("stockfish_jni" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(stockfish_jni SHARED
    stockfish_jni.cpp
    stockfish/benchmark.cpp
    stockfish/bitboard.cpp
    stockfish/engine.cpp
    stockfish/evaluate.cpp
    stockfish/memory.cpp
    stockfish/misc.cpp
    stockfish/movegen.cpp
    stockfish/movepick.cpp
    stockfish/position.cpp
    stockfish/score.cpp
    stockfish/search.cpp
    stockfish/thread.cpp
    stockfish/timeman.cpp
    stockfish/tt.cpp
    stockfish/tune.cpp
    stockfish/uci.cpp
    stockfish/ucioption.cpp
    stockfish/nnue/nnue_accumulator.cpp
    stockfish/nnue/nnue_misc.cpp
    stockfish/nnue/network.cpp
    stockfish/nnue/features/half_ka_v2_hm.cpp
    stockfish/nnue/features/full_threats.cpp
    stockfish/syzygy/tbprobe.cpp
)

target_include_directories(stockfish_jni PRIVATE
    stockfish
    stockfish/nnue
    stockfish/nnue/features
    stockfish/syzygy
    stockfish/incbin
)

target_compile_definitions(stockfish_jni PRIVATE
    NNUE_EMBEDDING_OFF
    USE_PTHREADS
    USE_POPCOUNT
)

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_definitions(stockfish_jni PRIVATE
        IS_64BIT
        USE_NEON=2
    )
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_definitions(stockfish_jni PRIVATE
        USE_NEON
    )
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    target_compile_definitions(stockfish_jni PRIVATE
        IS_64BIT
        USE_SSE41
        USE_SSE3
        USE_SSE2
    )
    target_compile_options(stockfish_jni PRIVATE
        -msse -msse2 -msse3 -mssse3 -msse4.1 -msse4.2 -mpopcnt
    )
elseif(${ANDROID_ABI} STREQUAL "x86")
    target_compile_definitions(stockfish_jni PRIVATE
        USE_SSE41
        USE_SSE3
        USE_SSE2
    )
    target_compile_options(stockfish_jni PRIVATE
        -msse -msse2 -msse3 -mssse3 -msse4.1 -msse4.2 -mpopcnt
    )
endif()

target_link_libraries(stockfish_jni
    android
    log
)
