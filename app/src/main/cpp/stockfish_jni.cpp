#include <jni.h>
#include <atomic>
#include <condition_variable>
#include <iostream>
#include <mutex>
#include <queue>
#include <streambuf>
#include <string>
#include <thread>

#include "stockfish/bitboard.h"
#include "stockfish/misc.h"
#include "stockfish/position.h"
#include "stockfish/tune.h"
#include "stockfish/uci.h"

using namespace Stockfish;

static std::mutex              g_mtx;
static std::condition_variable g_cv;
static std::queue<std::string> g_cmdQueue;
static std::queue<std::string> g_respQueue;
static std::thread             g_thread;
static std::atomic<bool>       g_running{false};
static std::atomic<int>        g_lastScore{0};

static std::streambuf* g_origCin  = nullptr;
static std::streambuf* g_origCout = nullptr;

struct InBuf : std::streambuf {
    std::string currentCmd;

    int underflow() override {
        if (gptr() < egptr())
            return traits_type::to_int_type(*gptr());
        std::unique_lock lock(g_mtx);
        g_cv.wait(lock, [] { return !g_cmdQueue.empty() || !g_running; });
        if (g_cmdQueue.empty())
            return traits_type::eof();
        currentCmd = std::move(g_cmdQueue.front());
        g_cmdQueue.pop();
        lock.unlock();
        currentCmd += '\n';
        char* p = currentCmd.data();
        setg(p, p, p + currentCmd.size());
        return traits_type::to_int_type(*gptr());
    }
};

struct OutBuf : std::streambuf {
    std::string buf;

    int overflow(int c) override {
        if (c != traits_type::eof()) {
            buf += char(c);
            if (c == '\n') {
                auto scorePos = buf.find("score cp ");
                if (scorePos != std::string::npos) {
                    try {
                        g_lastScore = std::stoi(buf.substr(scorePos + 9));
                    } catch (...) {
                        // malformed/edge line — keep previous score
                    }
                }
                std::lock_guard lock(g_mtx);
                g_respQueue.push(std::move(buf));
                buf.clear();
            }
        }
        return c;
    }

    int sync() override {
        if (!buf.empty()) {
            std::lock_guard lock(g_mtx);
            g_respQueue.push(std::move(buf));
            buf.clear();
        }
        return 0;
    }
};

static InBuf  g_inBuf;
static OutBuf g_outBuf;

static void uciThread() {
    std::cout.rdbuf(&g_outBuf);
    std::cin.rdbuf(&g_inBuf);

    Bitboards::init();
    Position::init();

    auto engineName = engine_info();
    std::cout << engineName << std::endl;

    static const char* dummyArgv[] = {"stockfish", nullptr};
    auto uci = std::make_unique<UCIEngine>(1, const_cast<char**>(dummyArgv));
    Tune::init(uci->engine_options());
    uci->loop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_engine_StockfishEngine_nativeInit(JNIEnv*, jobject) {
    if (g_running) return;
    g_running = true;
    g_origCin  = std::cin.rdbuf();
    g_origCout = std::cout.rdbuf();
    g_thread   = std::thread(uciThread);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_engine_StockfishEngine_nativeSendCommand(JNIEnv* env, jobject, jstring command) {
    const char* cmdStr = env->GetStringUTFChars(command, nullptr);
    {
        std::lock_guard lock(g_mtx);
        g_cmdQueue.push(cmdStr);
    }
    g_cv.notify_one();
    env->ReleaseStringUTFChars(command, cmdStr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_chessanalysis_engine_StockfishEngine_nativeGetResponse(JNIEnv* env, jobject) {
    std::lock_guard lock(g_mtx);
    if (g_respQueue.empty()) return env->NewStringUTF("");
    std::string resp = std::move(g_respQueue.front());
    g_respQueue.pop();
    return env->NewStringUTF(resp.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chessanalysis_engine_StockfishEngine_nativeShutdown(JNIEnv*, jobject) {
    {
        std::lock_guard lock(g_mtx);
        g_cmdQueue.push("quit");
    }
    g_cv.notify_all();
    if (g_thread.joinable()) g_thread.join();
    if (g_origCin)  std::cin.rdbuf(g_origCin);
    if (g_origCout) std::cout.rdbuf(g_origCout);
    g_running = false;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_chessanalysis_engine_StockfishEngine_nativeGetScore(JNIEnv*, jobject) {
    return static_cast<jint>(g_lastScore.load());
}
