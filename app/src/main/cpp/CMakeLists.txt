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

# --- AI Coach: on-device llama.cpp GGUF runner (built only when -DLLAMA_ENABLED=ON) ---
# The source is cloned HERE at CMake configure time (not in a Gradle task): Android Studio's CMake
# sync runs before Gradle's preBuild, so add_subdirectory() needs the dir to already exist. The tag
# comes from Gradle via -DLLAMA_TAG; a .fetched-tag marker triggers a re-clone when the tag changes.
if(LLAMA_ENABLED STREQUAL "ON")
    message(STATUS "AI Coach: building llama.cpp runner")
    set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
    if(NOT DEFINED LLAMA_TAG OR LLAMA_TAG STREQUAL "")
        set(LLAMA_TAG "b5050")  # real tag, supports Gemma 3 (>= ~b4875) AND the post-refactor vocab API
    endif()

    set(_llama_have_tag "")
    if(EXISTS "${LLAMA_SRC}/.fetched-tag")
        file(READ "${LLAMA_SRC}/.fetched-tag" _llama_have_tag)
        string(STRIP "${_llama_have_tag}" _llama_have_tag)
    endif()
    if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt" OR NOT _llama_have_tag STREQUAL LLAMA_TAG)
        find_program(GIT_EXECUTABLE git REQUIRED)
        if(EXISTS "${LLAMA_SRC}")
            message(STATUS "AI Coach: removing stale llama.cpp checkout (${_llama_have_tag})")
            file(REMOVE_RECURSE "${LLAMA_SRC}")
        endif()
        message(STATUS "AI Coach: cloning llama.cpp@${LLAMA_TAG} ...")
        execute_process(
            COMMAND "${GIT_EXECUTABLE}" clone --depth 1 --branch "${LLAMA_TAG}"
                    "https://github.com/ggerganov/llama.cpp.git" "${LLAMA_SRC}"
            RESULT_VARIABLE _llama_clone_rc)
        if(NOT _llama_clone_rc EQUAL 0)
            message(FATAL_ERROR "AI Coach: llama.cpp clone failed (rc=${_llama_clone_rc})")
        endif()
        file(WRITE "${LLAMA_SRC}/.fetched-tag" "${LLAMA_TAG}")
    endif()

    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    # PERF: the NDK's default armv8-a baseline has NO dotprod → Q4 matmul falls back to the slow generic
    # path (≈ "coach thinks forever"). GGML_NATIVE would probe the *host* (wrong in cross-compile), so we
    # pin the target arch with +dotprod (supported by ~all arm64 phones since 2017). i8mm is intentionally
    # omitted — it's armv8.6 and would SIGILL on older phones. GGML_CPU_AARCH64 enables Q4_0 repack kernels.
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
    set(GGML_CPU_AARCH64 ON CACHE BOOL "" FORCE)
    add_subdirectory(llama.cpp)

    add_library(llama_jni SHARED llama_jni.cpp)
    target_link_libraries(llama_jni llama android log)
else()
    message(STATUS "AI Coach: llama.cpp runner disabled (LLAMA_ENABLED=${LLAMA_ENABLED})")
endif()
