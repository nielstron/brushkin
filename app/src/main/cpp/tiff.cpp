// TIFF import (Android has no TIFF decoder): libtiff's RGBA interface handles the
// compressions, bit depths and color models, one page (directory) at a time.
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <jni.h>
#include <string>
#include <tiffio.h>
#include <vector>

namespace {
struct Memory {
  std::vector<unsigned char> data;
  toff_t position = 0;
  std::string error;
};

tmsize_t readProc(thandle_t h, void *buffer, tmsize_t size) {
  auto m = static_cast<Memory *>(h);
  if (m->position >= m->data.size())
    return 0;
  auto n = std::min<toff_t>(size, m->data.size() - m->position);
  memcpy(buffer, m->data.data() + m->position, n);
  m->position += n;
  return static_cast<tmsize_t>(n);
}
tmsize_t writeProc(thandle_t, void *, tmsize_t) { return 0; }
toff_t seekProc(thandle_t h, toff_t offset, int whence) {
  auto m = static_cast<Memory *>(h);
  toff_t base = whence == SEEK_CUR ? m->position : whence == SEEK_END ? m->data.size() : 0;
  m->position = base + offset;
  return m->position;
}
int closeProc(thandle_t) { return 0; }
toff_t sizeProc(thandle_t h) { return static_cast<Memory *>(h)->data.size(); }
int mapProc(thandle_t h, void **base, toff_t *size) {
  auto m = static_cast<Memory *>(h);
  *base = m->data.data();
  *size = m->data.size();
  return 1;
}
void unmapProc(thandle_t, void *, toff_t) {}
int errorHandler(TIFF *, void *user, const char *, const char *format, va_list args) {
  char message[512];
  vsnprintf(message, sizeof message, format, args);
  static_cast<Memory *>(user)->error = message;
  return 1;
}
int warningHandler(TIFF *, void *, const char *, const char *, va_list) { return 1; }

void failure(JNIEnv *env, const std::string &message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message.c_str());
}

TIFF *open(JNIEnv *env, jbyteArray encoded, Memory &m) {
  m.data.resize(env->GetArrayLength(encoded));
  env->GetByteArrayRegion(encoded, 0, m.data.size(), reinterpret_cast<jbyte *>(m.data.data()));
  auto options = TIFFOpenOptionsAlloc();
  TIFFOpenOptionsSetErrorHandlerExtR(options, errorHandler, &m);
  TIFFOpenOptionsSetWarningHandlerExtR(options, warningHandler, &m);
  TIFFOpenOptionsSetMaxSingleMemAlloc(options, 1024LL * 1024 * 1024);
  auto tif = TIFFClientOpenExt("image.tif", "rm", &m, readProc, writeProc, seekProc, closeProc,
                               sizeProc, mapProc, unmapProc, options);
  TIFFOpenOptionsFree(options);
  if (!tif)
    failure(env, m.error.empty() ? "Not a readable TIFF file" : m.error);
  return tif;
}

/** A DNG (camera raw) is a TIFF whose first directory has a DNGVersion tag (50706). */
bool isDng(const std::vector<unsigned char> &d) {
  if (d.size() < 8 || !((d[0] == 'I' && d[1] == 'I') || (d[0] == 'M' && d[1] == 'M')))
    return false;
  bool little = d[0] == 'I';
  auto u16 = [&](size_t p) -> uint32_t { return little ? d[p] | d[p + 1] << 8 : d[p] << 8 | d[p + 1]; };
  auto u32 = [&](size_t p) -> uint32_t {
    return little ? d[p] | d[p + 1] << 8 | d[p + 2] << 16 | static_cast<uint32_t>(d[p + 3]) << 24
                  : static_cast<uint32_t>(d[p]) << 24 | d[p + 1] << 16 | d[p + 2] << 8 | d[p + 3];
  };
  if (u16(2) != 42)
    return false;
  size_t ifd = u32(4);
  if (ifd + 2 > d.size())
    return false;
  uint32_t n = u16(ifd);
  for (uint32_t i = 0; i < n && ifd + 2 + (i + 1) * 12 <= d.size(); i++)
    if (u16(ifd + 2 + i * 12) == 50706)
      return true;
  return false;
}

/** Reduced-resolution copies (thumbnails) and transparency masks aren't pages of their own. */
bool isPage(TIFF *tif) {
  uint32_t type = 0;
  return !TIFFGetField(tif, TIFFTAG_SUBFILETYPE, &type) ||
         (type & (FILETYPE_REDUCEDIMAGE | FILETYPE_MASK)) == 0;
}
} // namespace

/**
 * [count, then directory, width, height per page]; count is -1 for a DNG (a camera raw that
 * Android decodes itself).
 */
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeDecode_tiffPages(JNIEnv *env, jobject, jbyteArray encoded) {
  Memory m;
  auto tif = open(env, encoded, m);
  if (!tif)
    return nullptr;
  std::vector<jint> out{0};
  if (isDng(m.data)) {
    out[0] = -1;
  } else {
    int directory = 0;
    do {
      uint32_t w = 0, h = 0;
      TIFFGetField(tif, TIFFTAG_IMAGEWIDTH, &w);
      TIFFGetField(tif, TIFFTAG_IMAGELENGTH, &h);
      if (isPage(tif) && w > 0 && h > 0) {
        out.push_back(directory);
        out.push_back(static_cast<jint>(std::min<uint32_t>(w, INT32_MAX)));
        out.push_back(static_cast<jint>(std::min<uint32_t>(h, INT32_MAX)));
        out[0]++;
      }
      directory++;
    } while (directory < 10000 && TIFFReadDirectory(tif));
  }
  TIFFClose(tif);
  auto a = env->NewIntArray(out.size());
  if (a)
    env->SetIntArrayRegion(a, 0, out.size(), out.data());
  return a;
}

/**
 * Page [directory] as [width, height, ARGB pixels], shrunk by a whole factor (averaging) to stay
 * within [maxDimension] and [maxPixels].
 */
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeDecode_tiff(JNIEnv *env, jobject, jbyteArray encoded,
                                                           jint directory, jint maxDimension,
                                                           jlong maxPixels) {
  Memory m;
  auto tif = open(env, encoded, m);
  if (!tif)
    return nullptr;
  struct Closer {
    TIFF *t;
    ~Closer() { TIFFClose(t); }
  } closer{tif};
  if (!TIFFSetDirectory(tif, static_cast<tdir_t>(directory))) {
    failure(env, "The TIFF page is missing");
    return nullptr;
  }
  char message[1024] = "";
  if (!TIFFRGBAImageOK(tif, message)) {
    failure(env, std::string("Unsupported TIFF: ") + message);
    return nullptr;
  }
  uint32_t w = 0, h = 0;
  TIFFGetField(tif, TIFFTAG_IMAGEWIDTH, &w);
  TIFFGetField(tif, TIFFTAG_IMAGELENGTH, &h);
  if (w == 0 || h == 0 || static_cast<uint64_t>(w) * h > 256ull * 1024 * 1024) {
    failure(env, "The TIFF image is too large to open");
    return nullptr;
  }
  std::vector<uint32_t> raster;
  try {
    raster.resize(static_cast<size_t>(w) * h);
  } catch (const std::bad_alloc &) {
    failure(env, "The TIFF image is too large to open");
    return nullptr;
  }
  if (!TIFFReadRGBAImageOriented(tif, w, h, raster.data(), ORIENTATION_TOPLEFT, 0)) {
    failure(env, m.error.empty() ? "Couldn't decode the TIFF image" : m.error);
    return nullptr;
  }
  uint32_t factor = 1;
  while (w / factor > static_cast<uint32_t>(maxDimension) || h / factor > static_cast<uint32_t>(maxDimension) ||
         static_cast<int64_t>(w / factor) * (h / factor) > maxPixels)
    factor++;
  uint32_t ow = std::max(1u, w / factor), oh = std::max(1u, h / factor);
  std::vector<jint> out(static_cast<size_t>(ow) * oh + 2);
  out[0] = static_cast<jint>(ow);
  out[1] = static_cast<jint>(oh);
  for (uint32_t y = 0; y < oh; y++)
    for (uint32_t x = 0; x < ow; x++) {
      // libtiff hands out premultiplied ABGR; average there, then un-premultiply for ARGB.
      uint32_t r = 0, g = 0, b = 0, a = 0, n = 0;
      for (uint32_t yy = y * factor; yy < std::min(h, (y + 1) * factor); yy++)
        for (uint32_t xx = x * factor; xx < std::min(w, (x + 1) * factor); xx++) {
          uint32_t p = raster[static_cast<size_t>(yy) * w + xx];
          r += TIFFGetR(p);
          g += TIFFGetG(p);
          b += TIFFGetB(p);
          a += TIFFGetA(p);
          n++;
        }
      r = (r + n / 2) / n;
      g = (g + n / 2) / n;
      b = (b + n / 2) / n;
      a = (a + n / 2) / n;
      if (a > 0 && a < 255) {
        r = std::min(255u, (r * 255 + a / 2) / a);
        g = std::min(255u, (g * 255 + a / 2) / a);
        b = std::min(255u, (b * 255 + a / 2) / a);
      } else if (a == 0)
        r = g = b = 0;
      out[static_cast<size_t>(y) * ow + x + 2] = static_cast<jint>((a << 24) | (r << 16) | (g << 8) | b);
    }
  auto result = env->NewIntArray(out.size());
  if (result)
    env->SetIntArrayRegion(result, 0, out.size(), out.data());
  return result;
}
