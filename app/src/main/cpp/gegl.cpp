#include <jni.h>
#include <gegl.h>
#include <gegl-paramspecs.h>
#include <vector>
#include <string>
#include <mutex>
#include <unordered_set>
#include <atomic>
#include <cstring>
#include <dlfcn.h>
#include <chrono>
#include <memory>
#include <algorithm>
#include <android/log.h>
#include <unistd.h>

typedef int cl_int_t;

static std::mutex gate;
static bool lastRenderUsedOpenCl = false;
// Counts OpenCL kernel launches of operations, so renders can report whether
// the GPU path ran. GEGL's color conversion kernels are not counted: they
// also run when an operation itself falls back to the CPU.
typedef cl_int_t (*EnqueueKernel)(void *, void *, unsigned, const size_t *, const size_t *,
                                 const size_t *, unsigned, const void *, void *);
typedef cl_int_t (*KernelInfo)(void *, unsigned, size_t, void *, size_t *);
extern "C" EnqueueKernel gegl_clEnqueueNDRangeKernel;
static EnqueueKernel realEnqueueKernel;
static KernelInfo kernelInfo;
static std::atomic<int> operationKernelLaunches{0};
static bool isConversionKernel(const char *name) {
    const bool prefix = !strncmp(name, "rgb", 3) || !strncmp(name, "ragab", 5) || name[0] == 'y';
    return prefix && strstr(name, "_to_");
}
static cl_int_t countingEnqueueKernel(void *queue, void *kernel, unsigned dims, const size_t *offset,
                                      const size_t *global, const size_t *local, unsigned events,
                                      const void *wait, void *event) {
    char name[128] = "";
    if (kernelInfo) kernelInfo(kernel, 0x1190 /* CL_KERNEL_FUNCTION_NAME */, sizeof name, name, nullptr);
    if (!isConversionKernel(name)) operationKernelLaunches++;
    return realEnqueueKernel(queue, kernel, dims, offset, global, local, events, wait, event);
}
// Host time spent blocked in OpenCL calls during a render (profiling only).
typedef cl_int_t (*FinishFn)(void *);
typedef cl_int_t (*ReadWriteFn)(void *, void *, unsigned, size_t, size_t, void *, unsigned, const void *, void *);
typedef void *(*MapFn)(void *, void *, unsigned, unsigned long long, size_t, size_t, unsigned, const void *, void *, cl_int_t *);
extern "C" FinishFn gegl_clFinish;
extern "C" ReadWriteFn gegl_clEnqueueReadBuffer, gegl_clEnqueueWriteBuffer;
extern "C" MapFn gegl_clEnqueueMapBuffer;
static FinishFn realFinish; static ReadWriteFn realRead, realWrite; static MapFn realMap;
static std::atomic<long long> clBlockedNs{0}, clFinishNs{0}, clReadNs{0}, clWriteNs{0}, clMapNs{0};
static long long nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
static cl_int_t timedFinish(void *q) { auto t=nowNs(); auto r=realFinish(q); auto d=nowNs()-t; clFinishNs+=d; clBlockedNs+=d; return r; }
static cl_int_t timedRead(void *q, void *m, unsigned b, size_t o, size_t n, void *p, unsigned e, const void *w, void *ev) {
    auto t=nowNs(); auto r=realRead(q,m,b,o,n,p,e,w,ev); auto d=nowNs()-t; clReadNs+=d; clBlockedNs+=d; return r; }
static cl_int_t timedWrite(void *q, void *m, unsigned b, size_t o, size_t n, void *p, unsigned e, const void *w, void *ev) {
    auto t=nowNs(); auto r=realWrite(q,m,b,o,n,p,e,w,ev); auto d=nowNs()-t; clWriteNs+=d; clBlockedNs+=d; return r; }
static void *timedMap(void *q, void *m, unsigned b, unsigned long long f, size_t o, size_t n, unsigned e, const void *w, void *ev, cl_int_t *err) {
    auto t=nowNs(); auto r=realMap(q,m,b,f,o,n,e,w,ev,err); auto d=nowNs()-t; clMapNs+=d; clBlockedNs+=d; return r; }
static void timeOpenClCalls() {
    if (realFinish || !gegl_clFinish) return;
    realFinish=gegl_clFinish; gegl_clFinish=timedFinish;
    if (gegl_clEnqueueReadBuffer) { realRead=gegl_clEnqueueReadBuffer; gegl_clEnqueueReadBuffer=timedRead; }
    if (gegl_clEnqueueWriteBuffer) { realWrite=gegl_clEnqueueWriteBuffer; gegl_clEnqueueWriteBuffer=timedWrite; }
    if (gegl_clEnqueueMapBuffer) { realMap=gegl_clEnqueueMapBuffer; gegl_clEnqueueMapBuffer=timedMap; }
}
static void countOpenClLaunches() {
    if (!gegl_clEnqueueNDRangeKernel || realEnqueueKernel) return;
    for (const char *library : {"libOpenCL.so", "libOpenCL-pixel.so"}) {
        if (void *handle = dlopen(library, RTLD_NOW | RTLD_NOLOAD)) {
            kernelInfo = reinterpret_cast<KernelInfo>(dlsym(handle, "clGetKernelInfo"));
            if (kernelInfo) break;
        }
    }
    realEnqueueKernel = gegl_clEnqueueNDRangeKernel;
    gegl_clEnqueueNDRangeKernel = countingEnqueueKernel;
}
static void fail(JNIEnv *env, const std::string &message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message.c_str());
}
static std::string utf(JNIEnv *env, jstring value) {
    const char *p = env->GetStringUTFChars(value, nullptr);
    std::string s(p); env->ReleaseStringUTFChars(value, p); return s;
}
static constexpr guint64 minTileCacheSize = 32ull << 20;
static guint64 fullTileCacheSize = 0;
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_initialize(JNIEnv *env, jobject, jstring operations, jstring bablPath, jstring swapPath) {
    std::lock_guard<std::mutex> lock(gate);
    g_setenv("GEGL_PATH", utf(env, operations).c_str(), TRUE);
    g_setenv("BABL_PATH", utf(env, bablPath).c_str(), TRUE);
    g_setenv("GEGL_SWAP", utf(env, swapPath).c_str(), TRUE);
    // Set the license before gegl_init: changing it afterwards makes GEGL rescan
    // GEGL_PATH and register every operation module a second time.
    g_object_set(gegl_config(), "application-license", "GPL3", nullptr);
    gegl_init(nullptr, nullptr);
    // A 32 MB tile cache makes GEGL swap (RLE-compressed) to disk from ~6 MP on, which doubled
    // live-effect and filter latency there. Use 1/16 of RAM, 32..512 MB (512 MB on 8+ GB phones).
    const long pages = sysconf(_SC_PHYS_PAGES), pageSize = sysconf(_SC_PAGESIZE);
    const guint64 ram = pages > 0 && pageSize > 0 ? guint64(pages) * guint64(pageSize) : 0;
    fullTileCacheSize = std::min<guint64>(512ull << 20, std::max<guint64>(minTileCacheSize, ram / 16));
    g_object_set(gegl_config(), "threads", 2, "tile-cache-size", fullTileCacheSize, nullptr);
}
// Loads the vendor's OpenCL driver and compiles GEGL's color kernels, so it must stay off the main
// thread. Holding `gate` keeps renders from seeing OpenCL half set up; they wait, then use the GPU.
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_enableOpenCl(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(gate);
    g_object_set(gegl_config(), "use-opencl", TRUE, nullptr);
    countOpenClLaunches();
    timeOpenClCalls();
}
// Lowering tile-cache-size makes GEGL trim the cache to its swap file right away. Not under
// `gate`: GEGL's cache has its own lock, and the main thread must not wait for a render.
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_shrinkTileCache(JNIEnv *, jobject, jboolean shrink) {
    if (!fullTileCacheSize) return;
    g_object_set(gegl_config(), "tile-cache-size", shrink ? minTileCacheSize : fullTileCacheSize, nullptr);
}
static GeglNode *createGraph(JNIEnv *env, jstring operation, jobjectArray keys, jdoubleArray values, GeglNode **output, bool allowOpenCl = true, bool benchmarkOpenCl = false) {
    const auto op = utf(env, operation);
    if (!gegl_has_operation(op.c_str())) { fail(env, "Unavailable GEGL operation: " + op); return nullptr; }
    GeglNode *graph = gegl_node_new();
    // Survey on a Pixel 6 Pro (Mali-G78) with the gegl-opencl fork, 1024x768,
    // GPU against the 2-thread CPU path: every listed operation matched the CPU
    // within 1/255 (opaque and alpha) and was at least 1.5x faster. Faster-but-
    // marginal kernels (alien-map, cell-noise, noise-hurl, opacity, threshold,
    // value-invert, ...) stay on the CPU. GEGL keeps its own CPU fallback for
    // parameter combinations a kernel does not support.
    static const std::unordered_set<std::string> acceleratedOps = {
        "gegl:bilateral-filter", "gegl:bloom", "gegl:box-blur",
        "gegl:brightness-contrast", "gegl:cartoon", "gegl:checkerboard",
        "gegl:color-enhance", "gegl:color-temperature", "gegl:deinterlace",
        "gegl:diffraction-patterns", "gegl:edge-laplace", "gegl:edge-sobel",
        "gegl:emboss", "gegl:exposure", "gegl:gaussian-blur",
        "gegl:gaussian-blur-selective", "gegl:gblur-1d", "gegl:high-pass",
        "gegl:invert-linear", "gegl:lens-blur", "gegl:lens-distortion",
        "gegl:levels", "gegl:mean-curvature-blur", "gegl:median-blur",
        "gegl:mono-mixer", "gegl:motion-blur-circular", "gegl:motion-blur-linear",
        "gegl:motion-blur-zoom", "gegl:newsprint", "gegl:noise-cie-lch",
        "gegl:noise-hsv", "gegl:noise-pick", "gegl:noise-reduction",
        "gegl:noise-rgb", "gegl:noise-slur", "gegl:noise-spread",
        "gegl:normal-map", "gegl:oilify", "gegl:photocopy", "gegl:pixelize",
        "gegl:polar-coordinates", "gegl:posterize", "gegl:ripple",
        "gegl:simplex-noise", "gegl:snn-mean", "gegl:softglow",
        "gegl:stretch-contrast", "gegl:stretch-contrast-hsv",
        "gegl:video-degradation", "gegl:vignette", "gegl:waves",
        "gegl:weighted-blend", "gegl:whirl-pinch", "svg:src-over",
    };
    const bool accelerated = acceleratedOps.count(op) != 0;
    g_object_set(graph, "use-opencl", allowOpenCl && (accelerated || benchmarkOpenCl), nullptr);
    GeglNode *filter = gegl_node_new_child(graph, "operation", op.c_str(), nullptr);
    const int count = env->GetArrayLength(keys);
    std::vector<jdouble> params(count);
    env->GetDoubleArrayRegion(values, 0, count, params.data());
    for (int i=0; i<count; i++) {
        auto key = (jstring)env->GetObjectArrayElement(keys, i);
        auto name = utf(env, key); env->DeleteLocalRef(key);
        GParamSpec *spec = gegl_node_find_property(filter, name.c_str());
        if (!spec) { g_object_unref(graph); fail(env, "Unknown GEGL parameter: " + name); return nullptr; }
        GValue value = G_VALUE_INIT;
        g_value_init(&value, spec->value_type);
        if (G_IS_PARAM_SPEC_DOUBLE(spec)) g_value_set_double(&value, params[i]);
        else if (G_IS_PARAM_SPEC_INT(spec)) g_value_set_int(&value, (int)params[i]);
        else if (G_IS_PARAM_SPEC_UINT(spec)) g_value_set_uint(&value, (guint)params[i]);
        else if (G_IS_PARAM_SPEC_BOOLEAN(spec)) g_value_set_boolean(&value, params[i] != 0);
        else if (G_IS_PARAM_SPEC_ENUM(spec)) g_value_set_enum(&value, (int)params[i]);
        else { g_value_unset(&value); g_object_unref(graph); fail(env, "Unsupported GEGL parameter: " + name); return nullptr; }
        if (g_param_value_validate(spec, &value)) {
            g_value_unset(&value); g_object_unref(graph); fail(env, "Out-of-range GEGL parameter: " + name); return nullptr;
        }
        gegl_node_set_property(filter, name.c_str(), &value); g_value_unset(&value);
    }
    *output = filter;
    return graph;
}
// Android's ARGB ints are B,G,R,A bytes in little-endian memory; babl's "R'G'B'A u8" is R,G,B,A.
// The R/B swap is folded into the copies that cross the JNI boundary anyway (in cache-sized
// bands), so no whole-image pass exists just for it. On input, fully transparent pixels become 0,
// as they always did. (A babl format with B'G'R'A component order would have no registered fast
// conversions, so babl would fall back to its slow reference path, and its path search could
// pick a different rounding than the u8 conversions used so far.)
static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "ARGB int byte order");
static inline uint32_t swapRB(uint32_t u) { return (u & 0xff00ff00u) | ((u & 255u)<<16) | ((u>>16)&255u); }
static inline uint32_t argbToRgba(uint32_t c) { return (c >> 24) == 0 ? 0u : swapRB(c); }
static const Babl *rgbaU8() { return babl_format("R'G'B'A u8"); }
static constexpr int64_t BAND_PIXELS = 1 << 16; // 256 KB staging: stays in cache

// Uploads rect.width×rect.height ARGB ints (row stride rect.width) from pixels into rect of buffer.
// Bands span whole GEGL tile rows, so tiles are overwritten in one go rather than first being
// allocated (zeroed) and then filled piece by piece.
static void uploadArgb(JNIEnv *env, jintArray pixels, GeglBuffer *buffer, const GeglRectangle &rect) {
    gint tileHeight = 128;
    g_object_get(buffer, "tile-height", &tileHeight, nullptr);
    const int tileRows = (int)std::max<int64_t>(1, BAND_PIXELS / (int64_t(rect.width)*tileHeight));
    const int rows = tileRows*tileHeight;
    std::unique_ptr<uint32_t[]> band(new uint32_t[size_t(std::min(rows, rect.height)) * rect.width]);
    for (int y=0; y<rect.height; y+=rows) {
        const int n = std::min(rows, rect.height-y);
        const jsize count = n*rect.width;
        env->GetIntArrayRegion(pixels, y*rect.width, count, reinterpret_cast<jint *>(band.get()));
        for (jsize i=0; i<count; i++) band[i] = argbToRgba(band[i]);
        const GeglRectangle r{rect.x, rect.y+y, rect.width, n};
        gegl_buffer_set(buffer, &r, 0, rgbaU8(), band.get(), GEGL_AUTO_ROWSTRIDE);
    }
}
// Copies a w×h region of an RGBA image (row stride `stride` pixels, starting at src) into pixels
// as ARGB ints with row stride w.
static void downloadArgb(JNIEnv *env, const uint32_t *src, int stride, int w, int h, jintArray pixels) {
    const int rows = (int)std::max<int64_t>(1, BAND_PIXELS / w);
    std::unique_ptr<uint32_t[]> band(new uint32_t[size_t(std::min(rows, h)) * w]);
    for (int y=0; y<h; y+=rows) {
        const int n = std::min(rows, h-y);
        for (int r=0; r<n; r++) {
            const uint32_t *in = src + int64_t(y+r)*stride;
            uint32_t *out = band.get() + int64_t(r)*w;
            for (int x=0; x<w; x++) out[x] = swapRB(in[x]);
        }
        env->SetIntArrayRegion(pixels, y*w, n*w, reinterpret_cast<const jint *>(band.get()));
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_render(JNIEnv *env, jobject, jintArray pixels, jint w, jint h,
                                                        jstring operation, jobjectArray keys, jdoubleArray values, jboolean useOpenCl, jboolean benchmarkOpenCl) {
    std::lock_guard<std::mutex> lock(gate);
    if (w <= 0 || h <= 0 || int64_t(w)*h != env->GetArrayLength(pixels) || env->GetArrayLength(keys) != env->GetArrayLength(values)) {
        fail(env, "Invalid GEGL image or parameters"); return nullptr;
    }
    const long long t0 = nowNs();
    GeglNode *filter;
    GeglNode *graph = createGraph(env, operation, keys, values, &filter, useOpenCl, benchmarkOpenCl);
    if (!graph) return nullptr;
    const long long tGraph = nowNs();
    const Babl *format = rgbaU8();
    GeglRectangle extent{0,0,w,h};
    GeglBuffer *buffer = gegl_buffer_new(&extent, format);
    uploadArgb(env, pixels, buffer, extent);
    const long long tCopyIn = nowNs();
    const long long tBufferSet = tCopyIn; // fused into copyIn
    GeglNode *input = gegl_node_new_child(graph, "operation", "gegl:buffer-source", "buffer", buffer, nullptr);
    if (gegl_node_has_pad(filter, "input")) gegl_node_link(input, filter);
    GeglBuffer *auxBuffer = nullptr;
    const auto op = utf(env, operation);
    if (benchmarkOpenCl && (op == "svg:src-over" || op == "gegl:weighted-blend" ||
                            op == "gegl:shadows-highlights-correction")) {
        // Separate deterministic auxiliary image for compositor benchmarks.
        std::vector<uint32_t> data(size_t(w)*h);
        env->GetIntArrayRegion(pixels, 0, w*h, reinterpret_cast<jint *>(data.data()));
        for (auto &c : data) c = argbToRgba(c);
        std::vector<uint32_t> aux(data.size());
        for (size_t i=0; i<data.size(); ++i) aux[i] = (data[data.size()-1-i] & 0x00ffffff) | 0x80000000;
        auxBuffer = gegl_buffer_new(&extent, format);
        gegl_buffer_set(auxBuffer, &extent, 0, format, aux.data(), GEGL_AUTO_ROWSTRIDE);
        GeglNode *auxInput = gegl_node_new_child(graph, "operation", "gegl:buffer-source", "buffer", auxBuffer, nullptr);
        gegl_node_connect_to(auxInput, "output", filter, "aux");
    }
    if (benchmarkOpenCl && op == "gegl:contrast-curve") {
        GeglCurve *curve = gegl_curve_new(0.0, 1.0);
        gegl_curve_add_point(curve, 0.0, 0.0);
        gegl_curve_add_point(curve, 0.25, 0.15);
        gegl_curve_add_point(curve, 0.75, 0.85);
        gegl_curve_add_point(curve, 1.0, 1.0);
        gegl_node_set(filter, "curve", curve, nullptr);
        g_object_unref(curve);
    }
    operationKernelLaunches = 0;
    clBlockedNs = 0; clFinishNs = 0; clReadNs = 0; clWriteNs = 0; clMapNs = 0;
    // Uninitialized: the blit writes every pixel.
    std::unique_ptr<uint32_t[]> out(new uint32_t[size_t(w)*h]);
    const long long tBlitStart = nowNs();
    gegl_node_blit(filter, 1.0, &extent, format, out.get(), GEGL_AUTO_ROWSTRIDE, GEGL_BLIT_DEFAULT);
    const long long tBlit = nowNs();
    lastRenderUsedOpenCl = operationKernelLaunches > 0;
    g_object_unref(graph); g_object_unref(buffer);
    if (auxBuffer) g_object_unref(auxBuffer);
    const long long tTeardown = nowNs();
    jintArray result = env->NewIntArray(w*h);
    if (result) downloadArgb(env, out.get(), w, w, h, result);
    const long long tEnd = nowNs();
    if (benchmarkOpenCl) {
        auto ms = [](long long ns) { return ns / 1e6; };
        __android_log_print(ANDROID_LOG_INFO, "BrushkinProfile",
            "%s %dx%d gpu=%d total=%.2f graph=%.2f copyIn=%.2f bufferSet=%.2f setup=%.2f blit=%.2f "
            "[clBlocked=%.2f finish=%.2f read=%.2f write=%.2f map=%.2f launches=%d] teardown=%.2f copyOut=%.2f",
            op.c_str(), w, h, lastRenderUsedOpenCl ? 1 : 0, ms(tEnd-t0), ms(tGraph-t0), ms(tCopyIn-tGraph),
            ms(tBufferSet-tCopyIn), ms(tBlitStart-tBufferSet), ms(tBlit-tBlitStart), ms(clBlockedNs), ms(clFinishNs),
            ms(clReadNs), ms(clWriteNs), ms(clMapNs), operationKernelLaunches.load(), ms(tTeardown-tBlit), ms(tEnd-tTeardown));
    }
    return result;
}

// Brushkin 0.6.3's render(), unchanged: the reference that EquivalenceTest compares the
// copy-reduced paths against. Not used by the app.
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_renderLegacy(JNIEnv *env, jobject, jintArray pixels, jint w, jint h,
                                                        jstring operation, jobjectArray keys, jdoubleArray values, jboolean useOpenCl, jboolean benchmarkOpenCl) {
    std::lock_guard<std::mutex> lock(gate);
    if (w <= 0 || h <= 0 || int64_t(w)*h != env->GetArrayLength(pixels) || env->GetArrayLength(keys) != env->GetArrayLength(values)) {
        fail(env, "Invalid GEGL image or parameters"); return nullptr;
    }
    const long long t0 = nowNs();
    GeglNode *filter;
    GeglNode *graph = createGraph(env, operation, keys, values, &filter, useOpenCl, benchmarkOpenCl);
    if (!graph) return nullptr;
    const long long tGraph = nowNs();
    std::vector<jint> data(w*h);
    env->GetIntArrayRegion(pixels, 0, w*h, data.data());
    // Convert Android's straight-alpha ARGB integers to babl's explicit RGBA byte order.
    auto swapRB = [](jint c) -> jint { auto u=static_cast<uint32_t>(c); return (u & 0xff00ff00u) | ((u & 255u)<<16) | ((u>>16)&255u); };
    for (auto &c : data) c = (static_cast<uint32_t>(c) >> 24) == 0 ? 0 : swapRB(c);
    const long long tCopyIn = nowNs();
    const Babl *format = babl_format("R'G'B'A u8");
    GeglRectangle extent{0,0,w,h};
    GeglBuffer *buffer = gegl_buffer_new(&extent, format);
    gegl_buffer_set(buffer, &extent, 0, format, data.data(), GEGL_AUTO_ROWSTRIDE);
    const long long tBufferSet = nowNs();
    GeglNode *input = gegl_node_new_child(graph, "operation", "gegl:buffer-source", "buffer", buffer, nullptr);
    if (gegl_node_has_pad(filter, "input")) gegl_node_link(input, filter);
    GeglBuffer *auxBuffer = nullptr;
    const auto op = utf(env, operation);
    if (benchmarkOpenCl && (op == "svg:src-over" || op == "gegl:weighted-blend" ||
                            op == "gegl:shadows-highlights-correction")) {
        // Separate deterministic auxiliary image for compositor benchmarks.
        std::vector<jint> aux(data.size());
        for (size_t i=0; i<data.size(); ++i) aux[i] = (data[data.size()-1-i] & 0x00ffffff) | 0x80000000;
        auxBuffer = gegl_buffer_new(&extent, format);
        gegl_buffer_set(auxBuffer, &extent, 0, format, aux.data(), GEGL_AUTO_ROWSTRIDE);
        GeglNode *auxInput = gegl_node_new_child(graph, "operation", "gegl:buffer-source", "buffer", auxBuffer, nullptr);
        gegl_node_connect_to(auxInput, "output", filter, "aux");
    }
    if (benchmarkOpenCl && op == "gegl:contrast-curve") {
        GeglCurve *curve = gegl_curve_new(0.0, 1.0);
        gegl_curve_add_point(curve, 0.0, 0.0);
        gegl_curve_add_point(curve, 0.25, 0.15);
        gegl_curve_add_point(curve, 0.75, 0.85);
        gegl_curve_add_point(curve, 1.0, 1.0);
        gegl_node_set(filter, "curve", curve, nullptr);
        g_object_unref(curve);
    }
    operationKernelLaunches = 0;
    clBlockedNs = 0; clFinishNs = 0; clReadNs = 0; clWriteNs = 0; clMapNs = 0;
    const long long tBlitStart = nowNs();
    gegl_node_blit(filter, 1.0, &extent, format, data.data(), GEGL_AUTO_ROWSTRIDE, GEGL_BLIT_DEFAULT);
    const long long tBlit = nowNs();
    lastRenderUsedOpenCl = operationKernelLaunches > 0;
    g_object_unref(graph); g_object_unref(buffer);
    if (auxBuffer) g_object_unref(auxBuffer);
    const long long tTeardown = nowNs();
    for (auto &c : data) c=swapRB(c);
    jintArray result = env->NewIntArray(w*h);
    if (result) env->SetIntArrayRegion(result, 0, w*h, data.data());
    const long long tEnd = nowNs();
    if (benchmarkOpenCl) {
        auto ms = [](long long ns) { return ns / 1e6; };
        __android_log_print(ANDROID_LOG_INFO, "BrushkinProfile",
            "%s %dx%d gpu=%d total=%.2f graph=%.2f copyIn=%.2f bufferSet=%.2f setup=%.2f blit=%.2f "
            "[clBlocked=%.2f finish=%.2f read=%.2f write=%.2f map=%.2f launches=%d] teardown=%.2f copyOut=%.2f",
            op.c_str(), w, h, lastRenderUsedOpenCl ? 1 : 0, ms(tEnd-t0), ms(tGraph-t0), ms(tCopyIn-tGraph),
            ms(tBufferSet-tCopyIn), ms(tBlitStart-tBufferSet), ms(tBlit-tBlitStart), ms(clBlockedNs), ms(clFinishNs),
            ms(clReadNs), ms(clWriteNs), ms(clMapNs), operationKernelLaunches.load(), ms(tTeardown-tBlit), ms(tEnd-tTeardown));
    }
    return result;
}

#include <json-glib/json-glib.h>
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_describe(JNIEnv *env, jobject, jstring operation) {
    std::lock_guard<std::mutex> lock(gate);
    auto op = utf(env, operation);
    if (!gegl_has_operation(op.c_str())) { fail(env, "Unavailable GEGL operation: " + op); return nullptr; }
    JsonBuilder *json = json_builder_new();
    auto text = [&](const char *key, const char *s) { json_builder_set_member_name(json,key); json_builder_add_string_value(json,s); };
    auto number = [&](const char *key, double n) { json_builder_set_member_name(json,key); json_builder_add_double_value(json,n); };
    json_builder_begin_array(json);
    guint count; GParamSpec **props = gegl_operation_list_properties(op.c_str(), &count);
    for (guint i=0; i<count; i++) {
        auto *p = props[i]; double low=0, high=1, def=0; const char *kind;
        if (G_IS_PARAM_SPEC_DOUBLE(p)) { auto *s=G_PARAM_SPEC_DOUBLE(p); low=s->minimum; high=s->maximum; def=s->default_value; kind="double"; }
        else if (G_IS_PARAM_SPEC_INT(p)) { auto *s=G_PARAM_SPEC_INT(p); low=s->minimum; high=s->maximum; def=s->default_value; kind="int"; }
        else if (G_IS_PARAM_SPEC_UINT(p)) { auto *s=G_PARAM_SPEC_UINT(p); low=s->minimum; high=s->maximum; def=s->default_value; kind="int"; }
        else if (G_IS_PARAM_SPEC_BOOLEAN(p)) { def=G_PARAM_SPEC_BOOLEAN(p)->default_value; kind="boolean"; }
        else if (G_IS_PARAM_SPEC_ENUM(p)) { auto *s=G_PARAM_SPEC_ENUM(p); low=s->enum_class->minimum; high=s->enum_class->maximum; def=s->default_value; kind="enum"; }
        else continue; // Color and object inputs need dedicated controls, not numeric sliders.
        const double allowedLow=low, allowedHigh=high;
        double gamma=1.0;
        if (GEGL_IS_PARAM_SPEC_DOUBLE(p)) {
            auto *s=GEGL_PARAM_SPEC_DOUBLE(p); low=s->ui_minimum; high=s->ui_maximum; gamma=s->ui_gamma;
        } else if (GEGL_IS_PARAM_SPEC_INT(p)) {
            auto *s=GEGL_PARAM_SPEC_INT(p); low=s->ui_minimum; high=s->ui_maximum; gamma=s->ui_gamma;
        }
        json_builder_begin_object(json);
        text("key",p->name); text("label",g_param_spec_get_nick(p)); text("kind",kind);
        number("min",low); number("max",high); number("default",def);
        number("allowedMin",allowedLow); number("allowedMax",allowedHigh); number("gamma",gamma);
        const char *unit=gegl_param_spec_get_property_key(p,"unit"); text("unit",unit ? unit : "");
        if (G_IS_PARAM_SPEC_ENUM(p)) {
            json_builder_set_member_name(json,"choices"); json_builder_begin_array(json);
            auto *ec=G_PARAM_SPEC_ENUM(p)->enum_class;
            for (guint j=0;j<ec->n_values;j++) {
                json_builder_begin_object(json); number("value",ec->values[j].value); text("label",ec->values[j].value_nick); json_builder_end_object(json);
            }
            json_builder_end_array(json);
        }
        json_builder_end_object(json);
    }
    g_free(props); json_builder_end_array(json);
    JsonNode *root=json_builder_get_root(json); char *encoded=json_to_string(root,FALSE);
    jstring result=env->NewStringUTF(encoded);
    g_free(encoded); json_node_free(root); g_object_unref(json); return result;
}

// Persistent graphs let GEGL propagate input changes through the actual operation's
// dependencies (including halos and global statistics), without guessing crop margins.
struct EffectSession {
    GeglNode *graph, *filter;
    GeglBuffer *input;
    GeglRectangle extent, dirty;
    bool regional, updated;
    // The last rendered rectangle (RGBA), read back tile by tile; reused across renders.
    std::unique_ptr<uint32_t[]> out;
    size_t outCapacity = 0;
    GeglRectangle rendered{0,0,0,0};
    ~EffectSession() { g_object_unref(graph); g_object_unref(input); }
};
static void invalidateEffect(GeglNode *, const GeglRectangle *rect, gpointer data) {
    auto *s=static_cast<EffectSession *>(data);
    GeglRectangle clipped;
    if (gegl_rectangle_intersect(&clipped,rect,&s->extent))
        gegl_rectangle_bounding_box(&s->dirty,&s->dirty,&clipped);
}
// Checks that the w×h rectangle at (x, y) lies in extent and that pixels holds at least w×h values.
static bool validRect(JNIEnv *env, const GeglRectangle &extent, jint x, jint y, jint w, jint h, jintArray pixels) {
    if (w<=0 || h<=0 || x<extent.x || y<extent.y || int64_t(x)+w>int64_t(extent.x)+extent.width ||
        int64_t(y)+h>int64_t(extent.y)+extent.height || int64_t(w)*h>env->GetArrayLength(pixels)) {
        fail(env,"Invalid GEGL rectangle"); return false;
    }
    return true;
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_createSession(JNIEnv *env, jobject, jint w, jint h,
    jstring operation, jobjectArray keys, jdoubleArray values, jboolean regional) {
    std::lock_guard<std::mutex> lock(gate);
    if (w<=0 || h<=0 || env->GetArrayLength(keys)!=env->GetArrayLength(values)) {
        fail(env,"Invalid GEGL session dimensions or parameters"); return 0;
    }
    GeglNode *filter;
    GeglNode *graph=createGraph(env,operation,keys,values,&filter);
    if (!graph) return 0;
    GeglRectangle extent{0,0,w,h};
    auto *buffer=gegl_buffer_new(&extent,rgbaU8());
    auto *input=gegl_node_new_child(graph,"operation","gegl:buffer-source","buffer",buffer,nullptr);
    gegl_node_link(input,filter);
    auto *session=new EffectSession{graph,filter,buffer,extent,extent,regional != 0,false};
    g_signal_connect(filter,"invalidated",G_CALLBACK(invalidateEffect),session);
    // Prepare the source's buffer-changed subscription before the first tile upload.
    gegl_node_get_bounding_box(filter);
    return reinterpret_cast<jlong>(session);
}
/** Uploads w×h ARGB ints (row stride w; the array may be larger) to the session input. */
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_updateSession(JNIEnv *env,jobject,jlong handle,
    jint x,jint y,jint w,jint h,jintArray pixels) {
    std::lock_guard<std::mutex> lock(gate);
    auto *s=reinterpret_cast<EffectSession *>(handle);
    if (!validRect(env,s->extent,x,y,w,h,pixels)) return;
    s->updated=true;
    uploadArgb(env,pixels,s->input,GeglRectangle{x,y,w,h});
}
/** Renders what changed since the last render; returns that rectangle as {x, y, w, h}. Its pixels
 * stay native until [readSession] fetches them. */
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_renderSession(JNIEnv *env,jobject,jlong handle) {
    std::lock_guard<std::mutex> lock(gate);
    auto *s=reinterpret_cast<EffectSession *>(handle);
    // Some GEGL operations (e.g. gblur-1d) do not expand change invalidation to
    // match their input dependencies. Invalidate fully unless regional updates
    // have been verified for this operation; the algorithm itself is unchanged.
    if (s->updated && !s->regional) {
        g_signal_emit_by_name(s->input,"changed",&s->extent);
        s->dirty=s->extent;
    }
    s->updated=false;
    const auto rect=s->dirty;
    const size_t count=size_t(rect.width)*rect.height;
    lastRenderUsedOpenCl = false;
    if (count) {
        if (count>s->outCapacity) { s->out.reset(new uint32_t[count]); s->outCapacity=count; }
        operationKernelLaunches = 0;
        gegl_node_blit(s->filter,1.0,&rect,rgbaU8(),s->out.get(),GEGL_AUTO_ROWSTRIDE,s->regional ? GEGL_BLIT_CACHE : GEGL_BLIT_DEFAULT);
        lastRenderUsedOpenCl = operationKernelLaunches > 0;
    }
    s->rendered=rect;
    s->dirty=GeglRectangle{0,0,0,0};
    jint header[4]={rect.x,rect.y,rect.width,rect.height};
    auto out=env->NewIntArray(4);
    if(out) env->SetIntArrayRegion(out,0,4,header);
    return out;
}
/** Copies a w×h part of the last rendered rectangle into pixels as ARGB ints (row stride w). */
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_readSession(JNIEnv *env,jobject,jlong handle,
    jint x,jint y,jint w,jint h,jintArray pixels) {
    std::lock_guard<std::mutex> lock(gate);
    auto *s=reinterpret_cast<EffectSession *>(handle);
    const auto &r=s->rendered;
    if (!validRect(env,r,x,y,w,h,pixels)) return;
    downloadArgb(env,s->out.get()+int64_t(y-r.y)*r.width+(x-r.x),r.width,w,h,pixels);
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_destroySession(JNIEnv *,jobject,jlong handle) {
    std::lock_guard<std::mutex> lock(gate);
    delete reinterpret_cast<EffectSession *>(handle);
}

// A layer's pixels kept as a GEGL buffer across destructive filter renders, so an apply only
// uploads the tiles that changed since the last one (see LayerBuffers.kt).
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_createBuffer(JNIEnv *env, jobject, jint w, jint h) {
    std::lock_guard<std::mutex> lock(gate);
    if (w<=0 || h<=0) { fail(env,"Invalid GEGL buffer size"); return 0; }
    GeglRectangle extent{0,0,w,h};
    return reinterpret_cast<jlong>(gegl_buffer_new(&extent,rgbaU8()));
}
/** Uploads w×h ARGB ints (row stride w; the array may be larger) to the buffer. */
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_uploadBuffer(JNIEnv *env, jobject, jlong handle,
    jint x, jint y, jint w, jint h, jintArray pixels) {
    std::lock_guard<std::mutex> lock(gate);
    auto *buffer=reinterpret_cast<GeglBuffer *>(handle);
    if (!validRect(env,*gegl_buffer_get_extent(buffer),x,y,w,h,pixels)) return;
    uploadArgb(env,pixels,buffer,GeglRectangle{x,y,w,h});
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_destroyBuffer(JNIEnv *, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gate);
    g_object_unref(reinterpret_cast<GeglBuffer *>(handle));
}
struct Rendered {
    std::unique_ptr<uint32_t[]> pixels; // RGBA
    GeglRectangle extent;
};
/** Renders the operation over the whole buffer, like render() does for an array. The result stays
 * native until [readRendered] fetches it tile by tile; free it with [destroyRendered]. */
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_renderBuffer(JNIEnv *env, jobject, jlong handle,
    jstring operation, jobjectArray keys, jdoubleArray values, jboolean useOpenCl, jboolean profile) {
    std::lock_guard<std::mutex> lock(gate);
    auto *buffer=reinterpret_cast<GeglBuffer *>(handle);
    if (env->GetArrayLength(keys)!=env->GetArrayLength(values)) { fail(env,"Invalid GEGL parameters"); return 0; }
    const long long t0=nowNs();
    GeglNode *filter;
    GeglNode *graph=createGraph(env,operation,keys,values,&filter,useOpenCl);
    if (!graph) return 0;
    // buffer-source marks its buffer as forked, so no operation processes it in place.
    GeglNode *input=gegl_node_new_child(graph,"operation","gegl:buffer-source","buffer",buffer,nullptr);
    if (gegl_node_has_pad(filter,"input")) gegl_node_link(input,filter);
    const GeglRectangle extent=*gegl_buffer_get_extent(buffer);
    auto *result=new Rendered{std::unique_ptr<uint32_t[]>(new uint32_t[size_t(extent.width)*extent.height]),extent};
    operationKernelLaunches = 0;
    clBlockedNs = 0;
    const long long tGraph=nowNs();
    gegl_node_blit(filter,1.0,&extent,rgbaU8(),result->pixels.get(),GEGL_AUTO_ROWSTRIDE,GEGL_BLIT_DEFAULT);
    const long long tBlit=nowNs();
    lastRenderUsedOpenCl = operationKernelLaunches > 0;
    g_object_unref(graph);
    if (profile) {
        auto ms=[](long long ns) { return ns/1e6; };
        __android_log_print(ANDROID_LOG_INFO,"BrushkinProfile","buffer %s %dx%d gpu=%d graph=%.2f blit=%.2f [clBlocked=%.2f launches=%d] teardown=%.2f",
            utf(env,operation).c_str(),extent.width,extent.height,lastRenderUsedOpenCl ? 1 : 0,ms(tGraph-t0),ms(tBlit-tGraph),
            ms(clBlockedNs),operationKernelLaunches.load(),ms(nowNs()-tBlit));
    }
    return reinterpret_cast<jlong>(result);
}
/** Copies a w×h part of a render into pixels as ARGB ints (row stride w). */
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_readRendered(JNIEnv *env, jobject, jlong handle,
    jint x, jint y, jint w, jint h, jintArray pixels) {
    auto *r=reinterpret_cast<Rendered *>(handle);
    if (!validRect(env,r->extent,x,y,w,h,pixels)) return;
    downloadArgb(env,r->pixels.get()+int64_t(y)*r->extent.width+x,r->extent.width,w,h,pixels);
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_destroyRendered(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Rendered *>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_openClActive(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(gate);
    gboolean active = FALSE;
    g_object_get(gegl_config(), "use-opencl", &active, nullptr);
    return active ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_lastRenderUsedOpenCl(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(gate);
    return lastRenderUsedOpenCl ? JNI_TRUE : JNI_FALSE;
}
