#include <algorithm>
#include <csetjmp>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <gif_lib.h>
#include <jni.h>
#include <jpeglib.h>
#include <vector>
#include <webp/demux.h>

static void failure(JNIEnv *env, const char *message) {
  env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
static std::vector<unsigned char> input(JNIEnv *env, jbyteArray data) {
  std::vector<unsigned char> b(env->GetArrayLength(data));
  env->GetByteArrayRegion(data, 0, b.size(),
                          reinterpret_cast<jbyte *>(b.data()));
  return b;
}
static jintArray integers(JNIEnv *env, const std::vector<jint> &data) {
  auto a = env->NewIntArray(data.size());
  if (a)
    env->SetIntArrayRegion(a, 0, data.size(), data.data());
  return a;
}
static jobjectArray frames(JNIEnv *env, int count, int w, int h, int loop) {
  auto out = env->NewObjectArray(count + 1, env->FindClass("[I"), nullptr);
  auto info = integers(env, {w, h, loop});
  if (out)
    env->SetObjectArrayElement(out, 0, info);
  env->DeleteLocalRef(info);
  return out;
}
static bool budget(JNIEnv *env, int w, int h, int count) {
  if (w <= 0 || h <= 0 || count <= 0 || count > 500 ||
      static_cast<int64_t>(w) * h * count > 40000000) {
    failure(env, "Animation is too large: at most 500 frames and 40 megapixels "
                 "across all frames can be opened. Insert it as an image to "
                 "use only the first frame.");
    return false;
  }
  return true;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeDecode_webp(JNIEnv *env, jobject,
                                                           jbyteArray encoded) {
  auto b = input(env, encoded);
  WebPData data{b.data(), b.size()};
  auto demux = WebPDemux(&data);
  if (!demux) {
    failure(env, "Invalid WebP image");
    return nullptr;
  }
  int w = WebPDemuxGetI(demux, WEBP_FF_CANVAS_WIDTH),
      h = WebPDemuxGetI(demux, WEBP_FF_CANVAS_HEIGHT),
      count = WebPDemuxGetI(demux, WEBP_FF_FRAME_COUNT),
      loop = WebPDemuxGetI(demux, WEBP_FF_LOOP_COUNT);
  WebPDemuxDelete(demux);
  if (!budget(env, w, h, count))
    return nullptr;
  auto decoder = WebPAnimDecoderNew(&data, nullptr);
  if (!decoder) {
    failure(env, "WebP animation could not be decoded");
    return nullptr;
  }
  auto out = frames(env, count, w, h, loop);
  if (!out) {
    WebPAnimDecoderDelete(decoder);
    return nullptr;
  }
  int previous = 0;
  for (int i = 1; i <= count; i++) {
    uint8_t *rgba = nullptr;
    int timestamp = 0;
    if (!WebPAnimDecoderGetNext(decoder, &rgba, &timestamp)) {
      WebPAnimDecoderDelete(decoder);
      failure(env, "Incomplete WebP animation");
      return nullptr;
    }
    std::vector<jint> frame(w * h + 1);
    frame[0] = timestamp - previous;
    previous = timestamp;
    for (int p = 0; p < w * h; p++)
      frame[p + 1] = (static_cast<uint32_t>(rgba[p * 4 + 3]) << 24) |
                     (rgba[p * 4] << 16) | (rgba[p * 4 + 1] << 8) |
                     rgba[p * 4 + 2];
    auto pixels = integers(env, frame);
    if (!pixels) {
      WebPAnimDecoderDelete(decoder);
      return nullptr;
    }
    env->SetObjectArrayElement(out, i, pixels);
    env->DeleteLocalRef(pixels);
  }
  WebPAnimDecoderDelete(decoder);
  return out;
}
struct GifInput {
  const unsigned char *data;
  size_t size;
  size_t offset;
};
static int gifRead(GifFileType *file, GifByteType *target, int n) {
  auto in = static_cast<GifInput *>(file->UserData);
  auto size = std::min(static_cast<size_t>(n), in->size - in->offset);
  memcpy(target, in->data + in->offset, size);
  in->offset += size;
  return size;
}
// Check image rectangles and aggregate decoded size before giflib allocates
// frame rasters.
static int gifCount(const std::vector<unsigned char> &b) {
  if (b.size() < 13)
    return -1;
  size_t p = 13;
  int count = 0, w = b[6] | (b[7] << 8), h = b[8] | (b[9] << 8);
  if (b[10] & 128)
    p += 3 * (1 << ((b[10] & 7) + 1));
  auto blocks = [&]() {
    while (p < b.size()) {
      auto n = b[p++];
      if (n == 0)
        return true;
      if (n > b.size() - p)
        return false;
      p += n;
    }
    return false;
  };
  while (p < b.size()) {
    int type = b[p++];
    if (type == 0x3b)
      return count;
    if (type == 0x21) {
      if (p == b.size())
        return -1;
      p++;
      if (!blocks())
        return -1;
    } else if (type == 0x2c) {
      if (b.size() - p < 9)
        return -1;
      int x = b[p] | (b[p + 1] << 8), y = b[p + 2] | (b[p + 3] << 8),
          fw = b[p + 4] | (b[p + 5] << 8), fh = b[p + 6] | (b[p + 7] << 8);
      auto flags = b[p + 8];
      p += 9;
      if (fw <= 0 || fh <= 0 || x + fw > w || y + fh > h)
        return -1;
      if (flags & 128)
        p += 3 * (1 << ((flags & 7) + 1));
      if (p >= b.size())
        return -1;
      p++;
      if (!blocks())
        return -1;
      count++;
    } else
      return -1;
  }
  return -1;
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeDecode_gif(JNIEnv *env, jobject,
                                                          jbyteArray encoded) {
  auto b = input(env, encoded);
  int count = gifCount(b);
  if (count < 1) {
    failure(env, "Invalid GIF animation");
    return nullptr;
  }
  int w = b[6] | (b[7] << 8), h = b[8] | (b[9] << 8);
  if (!budget(env, w, h, count))
    return nullptr;
  GifInput in{b.data(), b.size(), 0};
  int error;
  auto gif = DGifOpen(&in, gifRead, &error);
  if (!gif) {
    failure(env, "Invalid GIF image");
    return nullptr;
  }
  if (DGifSlurp(gif) == GIF_ERROR) {
    DGifCloseFile(gif, &error);
    failure(env, "Incomplete GIF animation");
    return nullptr;
  }
  int loop = 1; // No loop extension means play once.
  auto scanLoop = [&](ExtensionBlock *ext, int n) {
    for (int i = 0; i + 1 < n; i++)
      if (ext[i].Function == APPLICATION_EXT_FUNC_CODE &&
          ext[i].ByteCount == 11 &&
          memcmp(ext[i].Bytes, "NETSCAPE2.0", 11) == 0 &&
          ext[i + 1].ByteCount >= 3) {
        int repeats = ext[i + 1].Bytes[1] | (ext[i + 1].Bytes[2] << 8);
        loop = repeats ? repeats + 1 : 0;
      }
  };
  scanLoop(gif->ExtensionBlocks, gif->ExtensionBlockCount);
  for (int i = 0; i < count; i++)
    scanLoop(gif->SavedImages[i].ExtensionBlocks,
             gif->SavedImages[i].ExtensionBlockCount);
  auto out = frames(env, count, w, h, loop);
  if (!out) {
    DGifCloseFile(gif, &error);
    return nullptr;
  }
  jint background = 0;
  if (gif->SColorMap && gif->SBackGroundColor < gif->SColorMap->ColorCount) {
    auto rgb = gif->SColorMap->Colors[gif->SBackGroundColor];
    background = 0xff000000u | (rgb.Red << 16) | (rgb.Green << 8) | rgb.Blue;
  }
  GraphicsControlBlock first{DISPOSAL_UNSPECIFIED, false, 10,
                             NO_TRANSPARENT_COLOR};
  DGifSavedExtensionToGCB(gif, 0, &first);
  std::vector<jint> canvas(
      w * h, first.TransparentColor == NO_TRANSPARENT_COLOR ? background : 0),
      restore;
  GraphicsControlBlock previous{};
  GifImageDesc previousRect{};
  for (int i = 0; i < count; i++) {
    if (i && previous.DisposalMode == DISPOSE_BACKGROUND) {
      for (int y = previousRect.Top; y < previousRect.Top + previousRect.Height;
           y++)
        std::fill(
            canvas.begin() + y * w + previousRect.Left,
            canvas.begin() + y * w + previousRect.Left + previousRect.Width,
            previous.TransparentColor == NO_TRANSPARENT_COLOR ? background : 0);
    } else if (i && previous.DisposalMode == DISPOSE_PREVIOUS)
      canvas = restore;
    auto image = &gif->SavedImages[i];
    auto rect = image->ImageDesc;
    GraphicsControlBlock control{DISPOSAL_UNSPECIFIED, false, 10,
                                 NO_TRANSPARENT_COLOR};
    DGifSavedExtensionToGCB(gif, i, &control);
    if (control.DisposalMode == DISPOSE_PREVIOUS)
      restore = canvas;
    auto map = rect.ColorMap ? rect.ColorMap : gif->SColorMap;
    if (!map) {
      DGifCloseFile(gif, &error);
      failure(env, "GIF has no color table");
      return nullptr;
    }
    for (int y = 0; y < rect.Height; y++)
      for (int x = 0; x < rect.Width; x++) {
        int color = image->RasterBits[y * rect.Width + x];
        if (color == control.TransparentColor)
          continue;
        if (color >= map->ColorCount) {
          DGifCloseFile(gif, &error);
          failure(env, "Invalid GIF color index");
          return nullptr;
        }
        auto rgb = map->Colors[color];
        canvas[(y + rect.Top) * w + x + rect.Left] =
            0xff000000u | (rgb.Red << 16) | (rgb.Green << 8) | rgb.Blue;
      }
    std::vector<jint> frame;
    frame.reserve(w * h + 1);
    frame.push_back(control.DelayTime * 10);
    frame.insert(frame.end(), canvas.begin(), canvas.end());
    auto pixels = integers(env, frame);
    if (!pixels) {
      DGifCloseFile(gif, &error);
      return nullptr;
    }
    env->SetObjectArrayElement(out, i + 1, pixels);
    env->DeleteLocalRef(pixels);
    previous = control;
    previousRect = rect;
  }
  DGifCloseFile(gif, &error);
  return out;
}
struct JpegReadError {
  jpeg_error_mgr pub;
  jmp_buf jump;
  char message[JMSG_LENGTH_MAX];
};
static void jpegError(j_common_ptr c) {
  auto e = reinterpret_cast<JpegReadError *>(c->err);
  (*c->err->format_message)(c, e->message);
  longjmp(e->jump, 1);
}
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_nielstron_brushkin_export_NativeDecode_jpeg(JNIEnv *env, jobject,
                                                           jbyteArray encoded,
                                                           jint maxDimension,
                                                           jlong maxPixels) {
  auto data = input(env, encoded);
  jpeg_decompress_struct c{};
  JpegReadError error{};
  c.err = jpeg_std_error(&error.pub);
  error.pub.error_exit = jpegError;
  if (setjmp(error.jump)) {
    jpeg_destroy_decompress(&c);
    failure(env, error.message);
    return nullptr;
  }
  jpeg_create_decompress(&c);
  jpeg_mem_src(&c, data.data(), data.size());
  jpeg_read_header(&c, TRUE);
  bool cmyk = c.jpeg_color_space == JCS_CMYK || c.jpeg_color_space == JCS_YCCK;
  c.out_color_space = cmyk ? JCS_CMYK : JCS_RGB;
  c.scale_num = 1;
  c.scale_denom = 1;
  jpeg_calc_output_dimensions(&c);
  while (c.output_width > static_cast<unsigned>(maxDimension) ||
         c.output_height > static_cast<unsigned>(maxDimension) ||
         static_cast<int64_t>(c.output_width) * c.output_height > maxPixels) {
    c.scale_denom *= 2;
    if (c.scale_denom > 16) {
      jpeg_destroy_decompress(&c);
      failure(env, "JPEG dimensions exceed the import limit");
      return nullptr;
    }
    jpeg_calc_output_dimensions(&c);
  }
  jpeg_start_decompress(&c);
  int w = c.output_width, h = c.output_height;
  std::vector<jint> pixels(w * h + 2);
  pixels[0] = w;
  pixels[1] = h;
  std::vector<unsigned char> row(w * c.output_components);
  while (c.output_scanline < c.output_height) {
    int y = c.output_scanline;
    JSAMPROW rows = row.data();
    jpeg_read_scanlines(&c, &rows, 1);
    for (int x = 0; x < w; x++) {
      int r, g, b;
      if (cmyk) {
        auto p = row.data() + x * 4;
        if (c.saw_Adobe_marker) {
          r = (p[0] * p[3] + 127) / 255;
          g = (p[1] * p[3] + 127) / 255;
          b = (p[2] * p[3] + 127) / 255;
        } else {
          r = ((255 - p[0]) * (255 - p[3]) + 127) / 255;
          g = ((255 - p[1]) * (255 - p[3]) + 127) / 255;
          b = ((255 - p[2]) * (255 - p[3]) + 127) / 255;
        }
      } else {
        r = row[x * 3];
        g = row[x * 3 + 1];
        b = row[x * 3 + 2];
      }
      pixels[y * w + x + 2] = 0xff000000u | (r << 16) | (g << 8) | b;
    }
  }
  jpeg_finish_decompress(&c);
  jpeg_destroy_decompress(&c);
  return integers(env, pixels);
}
