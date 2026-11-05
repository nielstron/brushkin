#include <jni.h>
#include <gegl.h>
#include <gegl-paramspecs.h>
#include <vector>
#include <string>
#include <mutex>

static std::mutex gate;
static bool lastRenderUsedOpenCl = false;
static bool graphUsedOpenCl(GeglNode *node) {
    bool used = false;
    auto *operation = gegl_node_get_gegl_operation(node);
    if (operation) used = g_object_get_data(G_OBJECT(operation), "brushkin-opencl-used") != nullptr;
    GSList *children = gegl_node_get_children(node);
    for (GSList *child = children; child; child = child->next)
        used = graphUsedOpenCl(static_cast<GeglNode *>(child->data)) || used;
    g_slist_free(children);
    return used;
}
static void fail(JNIEnv *env, const std::string &message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message.c_str());
}
static std::string utf(JNIEnv *env, jstring value) {
    const char *p = env->GetStringUTFChars(value, nullptr);
    std::string s(p); env->ReleaseStringUTFChars(value, p); return s;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_initialize(JNIEnv *env, jobject, jstring operations, jstring bablPath, jstring swapPath) {
    std::lock_guard<std::mutex> lock(gate);
    g_setenv("GEGL_PATH", utf(env, operations).c_str(), TRUE);
    g_setenv("BABL_PATH", utf(env, bablPath).c_str(), TRUE);
    g_setenv("GEGL_USE_OPENCL", "gpu", TRUE);
    g_setenv("GEGL_SWAP", utf(env, swapPath).c_str(), TRUE);
    gegl_init(nullptr, nullptr);
    g_object_set(gegl_config(), "application-license", "GPL3", "threads", 2, "tile-cache-size", guint64(32*1024*1024), nullptr);
}
static GeglNode *createGraph(JNIEnv *env, jstring operation, jobjectArray keys, jdoubleArray values, GeglNode **output, bool allowOpenCl = true, bool benchmarkOpenCl = false) {
    const auto op = utf(env, operation);
    if (!gegl_has_operation(op.c_str())) { fail(env, "Unavailable GEGL operation: " + op); return nullptr; }
    GeglNode *graph = gegl_node_new();
    // Measured end-to-end on Pixel 6 Pro; each enabled kernel passed opaque
    // and alpha comparisons. GEGL retains its parameter-specific CPU fallback.
    // Gaussian keeps GEGL's FIR/IIR selection; only its FIR path uses OpenCL.
    // Kernels with output discrepancies remain CPU-only.
    const bool accelerated = op == "gegl:motion-blur-linear" ||
        op == "gegl:box-blur" || op == "gegl:bilateral-filter" ||
        op == "gegl:pixelize" || op == "gegl:edge-sobel" ||
        op == "gegl:alien-map" || op == "gegl:noise-hsv" ||
        op == "gegl:noise-hurl" || op == "gegl:vignette" ||
        op == "gegl:edge-laplace" || op == "gegl:gaussian-blur-selective" ||
        op == "gegl:oilify" || op == "gegl:cell-noise" ||
        op == "gegl:simplex-noise" || op == "gegl:diffraction-patterns" ||
        op == "gegl:gblur-1d" || op == "gegl:gaussian-blur";
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
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_render(JNIEnv *env, jobject, jintArray pixels, jint w, jint h,
                                                        jstring operation, jobjectArray keys, jdoubleArray values, jboolean useOpenCl, jboolean benchmarkOpenCl) {
    std::lock_guard<std::mutex> lock(gate);
    if (w <= 0 || h <= 0 || int64_t(w)*h != env->GetArrayLength(pixels) || env->GetArrayLength(keys) != env->GetArrayLength(values)) {
        fail(env, "Invalid GEGL image or parameters"); return nullptr;
    }
    GeglNode *filter;
    GeglNode *graph = createGraph(env, operation, keys, values, &filter, useOpenCl, benchmarkOpenCl);
    if (!graph) return nullptr;
    std::vector<jint> data(w*h);
    env->GetIntArrayRegion(pixels, 0, w*h, data.data());
    // Convert Android's straight-alpha ARGB integers to babl's explicit RGBA byte order.
    auto swapRB = [](jint c) -> jint { auto u=static_cast<uint32_t>(c); return (u & 0xff00ff00u) | ((u & 255u)<<16) | ((u>>16)&255u); };
    for (auto &c : data) c = (static_cast<uint32_t>(c) >> 24) == 0 ? 0 : swapRB(c);
    const Babl *format = babl_format("R'G'B'A u8");
    GeglRectangle extent{0,0,w,h};
    GeglBuffer *buffer = gegl_buffer_new(&extent, format);
    gegl_buffer_set(buffer, &extent, 0, format, data.data(), GEGL_AUTO_ROWSTRIDE);
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
    gegl_node_blit(filter, 1.0, &extent, format, data.data(), GEGL_AUTO_ROWSTRIDE, GEGL_BLIT_DEFAULT);
    lastRenderUsedOpenCl = graphUsedOpenCl(filter);
    g_object_unref(graph); g_object_unref(buffer);
    if (auxBuffer) g_object_unref(auxBuffer);
    for (auto &c : data) c=swapRB(c);
    jintArray result = env->NewIntArray(w*h);
    if (result) env->SetIntArrayRegion(result, 0, w*h, data.data());
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
    ~EffectSession() { g_object_unref(graph); g_object_unref(input); }
};
static jint swapChannels(jint c) {
    const auto u=static_cast<uint32_t>(c);
    return (u & 0xff00ff00u) | ((u & 255u)<<16) | ((u>>16)&255u);
}
static void invalidateEffect(GeglNode *, const GeglRectangle *rect, gpointer data) {
    auto *s=static_cast<EffectSession *>(data);
    GeglRectangle clipped;
    if (gegl_rectangle_intersect(&clipped,rect,&s->extent))
        gegl_rectangle_bounding_box(&s->dirty,&s->dirty,&clipped);
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
    auto *buffer=gegl_buffer_new(&extent,babl_format("R'G'B'A u8"));
    auto *input=gegl_node_new_child(graph,"operation","gegl:buffer-source","buffer",buffer,nullptr);
    gegl_node_link(input,filter);
    auto *session=new EffectSession{graph,filter,buffer,extent,extent,regional != 0,false};
    g_signal_connect(filter,"invalidated",G_CALLBACK(invalidateEffect),session);
    // Prepare the source's buffer-changed subscription before the first tile upload.
    gegl_node_get_bounding_box(filter);
    return reinterpret_cast<jlong>(session);
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_updateSession(JNIEnv *env,jobject,jlong handle,
    jint x,jint y,jint w,jint h,jintArray pixels) {
    std::lock_guard<std::mutex> lock(gate);
    auto *s=reinterpret_cast<EffectSession *>(handle);
    if (w<=0 || h<=0 || x<0 || y<0 || x+w>s->extent.width || y+h>s->extent.height || int64_t(w)*h!=env->GetArrayLength(pixels)) {
        fail(env,"Invalid GEGL update rectangle"); return;
    }
    std::vector<jint> data(w*h);
    env->GetIntArrayRegion(pixels,0,w*h,data.data());
    for (auto &c:data) c=static_cast<uint32_t>(c)>>24 ? swapChannels(c) : 0;
    GeglRectangle rect{x,y,w,h};
    s->updated=true;
    gegl_buffer_set(s->input,&rect,0,babl_format("R'G'B'A u8"),data.data(),GEGL_AUTO_ROWSTRIDE);
}
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
    const int count=rect.width*rect.height;
    std::vector<jint> data(count+4);
    data[0]=rect.x; data[1]=rect.y; data[2]=rect.width; data[3]=rect.height;
    lastRenderUsedOpenCl = false;
    if (count) {
        g_object_set_data(G_OBJECT(gegl_node_get_gegl_operation(s->filter)), "brushkin-opencl-used", nullptr);
        gegl_node_blit(s->filter,1.0,&rect,babl_format("R'G'B'A u8"),data.data()+4,GEGL_AUTO_ROWSTRIDE,s->regional ? GEGL_BLIT_CACHE : GEGL_BLIT_DEFAULT);
        lastRenderUsedOpenCl = g_object_get_data(G_OBJECT(gegl_node_get_gegl_operation(s->filter)), "brushkin-opencl-used") != nullptr;
        for (int i=4;i<count+4;i++) data[i]=swapChannels(data[i]);
    }
    s->dirty=GeglRectangle{0,0,0,0};
    auto out=env->NewIntArray(count+4);
    if(out) env->SetIntArrayRegion(out,0,count+4,data.data());
    return out;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_destroySession(JNIEnv *,jobject,jlong handle) {
    std::lock_guard<std::mutex> lock(gate);
    delete reinterpret_cast<EffectSession *>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_nielstron_brushkin_engine_NativeGegl_isOpenClActive(JNIEnv *, jobject) {
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
