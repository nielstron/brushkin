#include <csetjmp>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <jpeglib.h>
#include <png.h>
#include <string>
#include <vector>
#include <webp/encode.h>
#include <webp/mux.h>

static void fail(JNIEnv *env, const char *message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
static jbyteArray bytes(JNIEnv *env, const unsigned char *data, size_t n) {
  auto result = env->NewByteArray(n);
  if (result)
    env->SetByteArrayRegion(result, 0, n,
                            reinterpret_cast<const jbyte *>(data));
  return result;
}
static std::vector<unsigned char> rgba(JNIEnv *env, jintArray pixels, int w,
                                       int h, bool white = false) {
  std::vector<jint> src(w * h);
  env->GetIntArrayRegion(pixels, 0, w * h, src.data());
  std::vector<unsigned char> out(w * h * (white ? 3 : 4));
  for (size_t i = 0; i < src.size(); i++) {
    unsigned int p = src[i], a = p >> 24;
    int channels = white ? 3 : 4;
    for (int c = 0; c < 3; c++) {
      unsigned int v = (p >> (16 - c * 8)) & 255;
      out[i * channels + c] = white ? (v * a + 255 * (255 - a) + 127) / 255 : v;
    }
    if (!white)
      out[i * 4 + 3] = a;
  }
  return out;
}
struct JpegError {
  jpeg_error_mgr pub;
  jmp_buf jump;
  char message[JMSG_LENGTH_MAX];
};
static void jpegError(j_common_ptr c) {
  auto e = reinterpret_cast<JpegError *>(c->err);
  (*c->err->format_message)(c, e->message);
  longjmp(e->jump, 1);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_jpeg(
    JNIEnv *env, jobject, jintArray pixels, jint w, jint h, jint quality,
    jint smoothing, jboolean progressive, jboolean arithmetic,
    jboolean optimize, jint restart, jint sampling, jint dct,
    jbyteArray original, jboolean cmyk, jint resolution) {
  auto data = rgba(env, pixels, w, h, true);
  if (cmyk) {
    std::vector<unsigned char> separated(w * h * 4);
    for (int i = 0; i < w * h; i++) {
      unsigned int max = data[i * 3];
      if (data[i * 3 + 1] > max)
        max = data[i * 3 + 1];
      if (data[i * 3 + 2] > max)
        max = data[i * 3 + 2];
      for (int c = 0; c < 3; c++)
        separated[i * 4 + c] =
            max ? (data[i * 3 + c] * 255 + max / 2) / max : 255;
      separated[i * 4 + 3] = max;
    }
    data.swap(separated);
  }
  jpeg_compress_struct c{};
  JpegError error{};
  c.err = jpeg_std_error(&error.pub);
  error.pub.error_exit = jpegError;
  unsigned char *out = nullptr;
  unsigned long size = 0;
  if (setjmp(error.jump)) {
    jpeg_destroy_compress(&c);
    free(out);
    fail(env, error.message);
    return nullptr;
  }
  jpeg_create_compress(&c);
  jpeg_mem_dest(&c, &out, &size);
  c.image_width = w;
  c.image_height = h;
  c.input_components = cmyk ? 4 : 3;
  c.in_color_space = cmyk ? JCS_CMYK : JCS_RGB;
  jpeg_set_defaults(&c);
  c.density_unit = 1;
  c.X_density = resolution;
  c.Y_density = resolution;
  if (cmyk)
    c.write_Adobe_marker = TRUE;
  jpeg_set_quality(&c, quality, TRUE);
  if (original) { // Reuse original quantization tables, as GIMP's "original
                  // quality" option does.
    auto n = env->GetArrayLength(original);
    std::vector<unsigned char> encoded(n);
    env->GetByteArrayRegion(original, 0, n,
                            reinterpret_cast<jbyte *>(encoded.data()));
    jpeg_decompress_struct d{};
    d.err = &error.pub;
    jpeg_create_decompress(&d);
    jpeg_mem_src(&d, encoded.data(), n);
    jpeg_read_header(&d, TRUE);
    for (int i = 0; i < NUM_QUANT_TBLS; i++)
      if (d.quant_tbl_ptrs[i]) {
        unsigned int table[DCTSIZE2];
        for (int j = 0; j < DCTSIZE2; j++)
          table[j] = d.quant_tbl_ptrs[i]->quantval[j];
        jpeg_add_quant_table(&c, i, table, 100, TRUE);
      }
    jpeg_destroy_decompress(&d);
  }
  c.smoothing_factor = smoothing;
  c.optimize_coding = optimize;
  c.arith_code = arithmetic;
  c.restart_in_rows = restart;
  c.dct_method = dct == 1 ? JDCT_IFAST : dct == 2 ? JDCT_FLOAT : JDCT_ISLOW;
  if (!cmyk) {
    c.comp_info[0].h_samp_factor = (sampling == 1 || sampling == 3) ? 2 : 1;
    c.comp_info[0].v_samp_factor = (sampling == 2 || sampling == 3) ? 2 : 1;
  }
  if (progressive)
    jpeg_simple_progression(&c);
  jpeg_start_compress(&c, TRUE);
  while (c.next_scanline < c.image_height) {
    JSAMPROW row = data.data() + c.next_scanline * w * (cmyk ? 4 : 3);
    jpeg_write_scanlines(&c, &row, 1);
  }
  jpeg_finish_compress(&c);
  auto result = bytes(env, out, size);
  jpeg_destroy_compress(&c);
  free(out);
  return result;
}
static void pngWrite(png_structp png, png_bytep data, png_size_t n) {
  auto out = static_cast<std::vector<unsigned char> *>(png_get_io_ptr(png));
  out->insert(out->end(), data, data + n);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_png(
    JNIEnv *env, jobject, jintArray pixels, jint w, jint h, jint format,
    jint compression, jboolean interlace) {
  auto src = rgba(env, pixels, w, h);
  bool gray = format >= 5, alpha = format == 0 || format == 2 || format == 4 ||
                                   format == 6 || format == 8;
  int depth =
      (format == 3 || format == 4 || format == 7 || format == 8) ? 16 : 8;
  int channels = (gray ? 1 : 3) + (alpha ? 1 : 0);
  std::vector<unsigned char> converted(w * h * channels * (depth / 8));
  size_t index = 0;
  for (int i = 0; i < w * h; i++) {
    auto p = &src[i * 4];
    unsigned char g = (p[0] * 299 + p[1] * 587 + p[2] * 114 + 500) / 1000;
    for (int c = 0; c < channels; c++) {
      int value = (alpha && c == channels - 1) ? p[3] : (gray ? g : p[c]);
      if (!alpha)
        value = (value * p[3] + 255 * (255 - p[3]) + 127) / 255;
      converted[index++] = value;
      if (depth == 16)
        converted[index++] = value;
    }
  }
  std::vector<unsigned char> out;
  auto png =
      png_create_write_struct(PNG_LIBPNG_VER_STRING, nullptr, nullptr, nullptr);
  auto info = png_create_info_struct(png);
  if (setjmp(png_jmpbuf(png))) {
    png_destroy_write_struct(&png, &info);
    fail(env, "PNG encoding failed");
    return nullptr;
  }
  png_set_write_fn(png, &out, pngWrite, nullptr);
  int type = gray ? (alpha ? PNG_COLOR_TYPE_GRAY_ALPHA : PNG_COLOR_TYPE_GRAY)
                  : (alpha ? PNG_COLOR_TYPE_RGBA : PNG_COLOR_TYPE_RGB);
  png_set_IHDR(png, info, w, h, depth, type,
               interlace ? PNG_INTERLACE_ADAM7 : PNG_INTERLACE_NONE,
               PNG_COMPRESSION_TYPE_DEFAULT, PNG_FILTER_TYPE_DEFAULT);
  png_set_compression_level(png, compression);
  png_write_info(png, info);
  std::vector<png_bytep> rows(h);
  for (int y = 0; y < h; y++)
    rows[y] = converted.data() + y * w * channels * (depth / 8);
  png_write_image(png, rows.data());
  png_write_end(png, info);
  png_destroy_write_struct(&png, &info);
  return bytes(env, out.data(), out.size());
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_webp(
    JNIEnv *env, jobject, jintArray pixels, jint w, jint h, jboolean lossless,
    jint quality, jint alphaQuality, jint preset, jboolean sharp) {
  auto data = rgba(env, pixels, w, h);
  WebPConfig config;
  WebPConfigPreset(&config, static_cast<WebPPreset>(preset), quality);
  config.lossless = lossless;
  config.alpha_quality = alphaQuality;
  config.use_sharp_yuv = sharp;
  config.exact = 1;
  WebPPicture pic;
  WebPPictureInit(&pic);
  pic.width = w;
  pic.height = h;
  pic.use_argb = 1;
  WebPMemoryWriter writer;
  WebPMemoryWriterInit(&writer);
  pic.writer = WebPMemoryWrite;
  pic.custom_ptr = &writer;
  if (!WebPPictureImportRGBA(&pic, data.data(), w * 4) ||
      !WebPEncode(&config, &pic)) {
    WebPPictureFree(&pic);
    WebPMemoryWriterClear(&writer);
    fail(env, "WebP encoding failed");
    return nullptr;
  }
  auto result = bytes(env, writer.mem, writer.size);
  WebPPictureFree(&pic);
  WebPMemoryWriterClear(&writer);
  return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_webpMetadata(
    JNIEnv *env, jobject, jbyteArray encoded, jbyteArray exif, jbyteArray xmp,
    jbyteArray profile) {
  auto n = env->GetArrayLength(encoded);
  std::vector<unsigned char> input(n);
  env->GetByteArrayRegion(encoded, 0, n,
                          reinterpret_cast<jbyte *>(input.data()));
  WebPData image{input.data(), input.size()};
  auto mux = WebPMuxCreate(&image, 1);
  if (!mux) {
    fail(env, "Invalid WebP image");
    return nullptr;
  }
  auto add = [&](const char *type, jbyteArray value) {
    if (!value)
      return;
    auto size = env->GetArrayLength(value);
    std::vector<unsigned char> data(size);
    env->GetByteArrayRegion(value, 0, size,
                            reinterpret_cast<jbyte *>(data.data()));
    WebPData chunk{data.data(), data.size()};
    WebPMuxSetChunk(mux, type, &chunk, 1);
  };
  add("EXIF", exif);
  add("XMP ", xmp);
  add("ICCP", profile);
  WebPData out;
  WebPDataInit(&out);
  auto status = WebPMuxAssemble(mux, &out);
  WebPMuxDelete(mux);
  if (status != WEBP_MUX_OK) {
    fail(env, "WebP metadata writing failed");
    return nullptr;
  }
  auto result = bytes(env, out.bytes, out.size);
  WebPDataClear(&out);
  return result;
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_animationStart(
    JNIEnv *env, jobject, jint w, jint h, jint loops, jboolean minimize,
    jint distance) {
  WebPAnimEncoderOptions options;
  WebPAnimEncoderOptionsInit(&options);
  options.anim_params.loop_count = loops;
  options.minimize_size = minimize;
  options.kmax = distance;
  options.kmin = distance / 2 + 1;
  auto encoder = WebPAnimEncoderNew(w, h, &options);
  if (!encoder)
    fail(env, "Could not start WebP animation");
  return reinterpret_cast<jlong>(encoder);
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_animationAdd(
    JNIEnv *env, jobject, jlong handle, jintArray pixels, jint w, jint h,
    jint timestamp, jboolean lossless, jint quality, jint alphaQuality,
    jint preset, jboolean sharp) {
  auto data = rgba(env, pixels, w, h);
  WebPConfig config;
  WebPConfigPreset(&config, static_cast<WebPPreset>(preset), quality);
  config.lossless = lossless;
  config.alpha_quality = alphaQuality;
  config.use_sharp_yuv = sharp;
  config.exact = 1;
  WebPPicture pic;
  WebPPictureInit(&pic);
  pic.width = w;
  pic.height = h;
  pic.use_argb = 1;
  auto encoder = reinterpret_cast<WebPAnimEncoder *>(handle);
  if (!WebPPictureImportRGBA(&pic, data.data(), w * 4) ||
      !WebPAnimEncoderAdd(encoder, &pic, timestamp, &config))
    fail(env, WebPAnimEncoderGetError(encoder));
  WebPPictureFree(&pic);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_animationFinish(
    JNIEnv *env, jobject, jlong handle, jint timestamp) {
  auto encoder = reinterpret_cast<WebPAnimEncoder *>(handle);
  WebPData out;
  WebPDataInit(&out);
  if (!WebPAnimEncoderAdd(encoder, nullptr, timestamp, nullptr) ||
      !WebPAnimEncoderAssemble(encoder, &out)) {
    fail(env, WebPAnimEncoderGetError(encoder));
    return nullptr;
  }
  auto result = bytes(env, out.bytes, out.size);
  WebPDataClear(&out);
  return result;
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_animationClose(
    JNIEnv *, jobject, jlong handle) {
  WebPAnimEncoderDelete(reinterpret_cast<WebPAnimEncoder *>(handle));
}
#include <gif_lib.h>
extern "C" int GifQuantizeBuffer(unsigned int, unsigned int, int *,
                                 const GifByteType *, const GifByteType *,
                                 const GifByteType *, GifByteType *,
                                 GifColorType *);
struct GifOutput {
  std::vector<unsigned char> data;
  GifFileType *gif;
};
static int gifWrite(GifFileType *gif, const GifByteType *data, int n) {
  auto out = static_cast<GifOutput *>(gif->UserData);
  out->data.insert(out->data.end(), data, data + n);
  return n;
}
extern "C" JNIEXPORT jlong JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_gifStart(
    JNIEnv *env, jobject, jint w, jint h, jint repeats, jstring comment) {
  auto output = new GifOutput();
  int error;
  output->gif = EGifOpen(output, gifWrite, &error);
  if (!output->gif) {
    delete output;
    fail(env, "Could not start GIF");
    return 0;
  }
  EGifSetGifVersion(output->gif, true);
  EGifPutScreenDesc(output->gif, w, h, 8, 255, nullptr);
  if (repeats >= 0) {
    unsigned char count[3] = {1, static_cast<unsigned char>(repeats & 255),
                              static_cast<unsigned char>((repeats >> 8) & 255)};
    EGifPutExtensionLeader(output->gif, APPLICATION_EXT_FUNC_CODE);
    EGifPutExtensionBlock(output->gif, 11, "NETSCAPE2.0");
    EGifPutExtensionBlock(output->gif, 3, count);
    EGifPutExtensionTrailer(output->gif);
  }
  const char *text = env->GetStringUTFChars(comment, nullptr);
  if (text[0])
    EGifPutComment(output->gif, text);
  env->ReleaseStringUTFChars(comment, text);
  return reinterpret_cast<jlong>(output);
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_gifAdd(
    JNIEnv *env, jobject, jlong handle, jintArray pixels, jint w, jint h,
    jint delay, jint disposal, jboolean interlace) {
  auto output = reinterpret_cast<GifOutput *>(handle);
  auto data = rgba(env, pixels, w, h);
  std::vector<GifByteType> r(w * h), g(w * h), b(w * h), indices(w * h);
  bool transparent = false;
  for (int i = 0; i < w * h; i++) {
    r[i] = data[i * 4];
    g[i] = data[i * 4 + 1];
    b[i] = data[i * 4 + 2];
    if (data[i * 4 + 3] < 128)
      transparent = true;
  }
  auto map = GifMakeMapObject(256, nullptr);
  int colors = transparent ? 255 : 256;
  if (!map || GifQuantizeBuffer(w, h, &colors, r.data(), g.data(), b.data(),
                                indices.data(), map->Colors) == GIF_ERROR) {
    if (map)
      GifFreeMapObject(map);
    fail(env, "GIF color quantization failed");
    return;
  }
  // giflib's quantizer bins at 5-bit precision; recover each palette centroid
  // from the original 8-bit samples.
  uint64_t sums[256][4]{};
  for (int i = 0; i < w * h; i++)
    if (data[i * 4 + 3] >= 128) {
      auto color = indices[i];
      sums[color][0] += data[i * 4];
      sums[color][1] += data[i * 4 + 1];
      sums[color][2] += data[i * 4 + 2];
      sums[color][3]++;
    }
  for (int i = 0; i < 256; i++)
    if (sums[i][3]) {
      auto n = sums[i][3];
      map->Colors[i].Red = (sums[i][0] + n / 2) / n;
      map->Colors[i].Green = (sums[i][1] + n / 2) / n;
      map->Colors[i].Blue = (sums[i][2] + n / 2) / n;
    }
  if (transparent)
    for (int i = 0; i < w * h; i++)
      if (data[i * 4 + 3] < 128)
        indices[i] = 255;
  GraphicsControlBlock control{disposal, false, (delay + 5) / 10,
                               transparent ? 255 : NO_TRANSPARENT_COLOR};
  unsigned char extension[4];
  EGifGCBToExtension(&control, extension);
  EGifPutExtension(output->gif, GRAPHICS_EXT_FUNC_CODE, 4, extension);
  bool ok =
      EGifPutImageDesc(output->gif, 0, 0, w, h, interlace, map) != GIF_ERROR;
  if (interlace) {
    int offsets[4] = {0, 4, 2, 1}, steps[4] = {8, 8, 4, 2};
    for (int pass = 0; pass < 4; pass++)
      for (int y = offsets[pass]; y < h; y += steps[pass])
        ok = ok &&
             EGifPutLine(output->gif, indices.data() + y * w, w) != GIF_ERROR;
  } else
    for (int y = 0; y < h; y++)
      ok = ok &&
           EGifPutLine(output->gif, indices.data() + y * w, w) != GIF_ERROR;
  GifFreeMapObject(map);
  if (!ok)
    fail(env, "GIF encoding failed");
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_gifFinish(JNIEnv *env,
                                                                jobject,
                                                                jlong handle) {
  auto out = reinterpret_cast<GifOutput *>(handle);
  int error;
  int result = EGifCloseFile(out->gif, &error);
  out->gif = nullptr;
  if (result == GIF_ERROR) {
    fail(env, "GIF finalization failed");
    return nullptr;
  }
  return bytes(env, out->data.data(), out->data.size());
}
extern "C" JNIEXPORT void JNICALL
Java_io_github_nielstron_brushkin_export_NativeExport_gifClose(JNIEnv *,
                                                               jobject,
                                                               jlong handle) {
  auto out = reinterpret_cast<GifOutput *>(handle);
  if (out->gif) {
    int error;
    EGifCloseFile(out->gif, &error);
  }
  delete out;
}
