package fun.fengwk.kkstudio.platform.harness.model;

import java.awt.geom.AffineTransform;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * JPEG 显示方向：从 APP1 EXIF 段读取 0x0112 orientation，并按显示方向给出坐标变换。
 *
 * <p>只解析 JPEG：PNG/GIF 等格式没有标准方向元数据，方向固定为 1。没有 EXIF 段时同样返回 1；EXIF 段存在但结构非法（字节序、魔数、 越界偏移、非法
 * orientation 值）时显式失败，绝不猜测方向后输出可能旋转错误的图片。
 */
final class JpegExifOrientation {

  /** 不旋转、不镜像。 */
  static final int IDENTITY = 1;

  private static final String JPEG = "image/jpeg";
  private static final int SOI = 0xD8;
  private static final int SOS = 0xDA;
  private static final int EOI = 0xD9;
  private static final int APP1 = 0xE1;
  private static final int TEM = 0x01;
  private static final int ORIENTATION_TAG = 0x0112;
  private static final int SHORT_TYPE = 3;
  private static final byte[] EXIF_PREFIX = {'E', 'x', 'i', 'f', 0, 0};

  private JpegExifOrientation() {}

  /** 解析 {@code mediaType} 的显示方向；非 JPEG 或没有 EXIF 时返回 {@link #IDENTITY}。 */
  static int read(String mediaType, byte[] bytes) {
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(bytes, "bytes");
    if (!JPEG.equals(mediaType)) {
      return IDENTITY;
    }
    if (bytes.length < 2 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != SOI) {
      // 不是 JPEG 字节流：交给解码器给出与媒体类型一致的显式失败。
      return IDENTITY;
    }
    int offset = 2;
    while (offset + 4 <= bytes.length) {
      if ((bytes[offset] & 0xFF) != 0xFF) {
        throw malformed("segment marker must start with 0xFF");
      }
      int marker = bytes[offset + 1] & 0xFF;
      if (marker == SOI || marker == TEM || (marker >= 0xD0 && marker <= 0xD7)) {
        offset += 2;
        continue;
      }
      if (marker == SOS || marker == EOI) {
        // 图像数据之前没有任何 EXIF 段：按未旋转处理。
        return IDENTITY;
      }
      int length = ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
      if (length < 2 || offset + 2 + length > bytes.length) {
        throw malformed("segment length is out of bounds");
      }
      if (marker == APP1) {
        int payload = offset + 4;
        int payloadLength = length - 2;
        if (startsWith(bytes, payload, payloadLength, EXIF_PREFIX)) {
          return parseTiff(bytes, payload + EXIF_PREFIX.length, payloadLength - EXIF_PREFIX.length);
        }
      }
      offset += 2 + length;
    }
    return IDENTITY;
  }

  /** 方向是否会交换宽高（5..8 为镜像/旋转 90 度的四种组合）。 */
  static boolean swapsAxes(int orientation) {
    return orientation >= 5 && orientation <= 8;
  }

  /** 源坐标 → 显示坐标的方向变换：镜像与旋转按 EXIF 定义组合，显示尺寸见 {@link #swapsAxes(int)}。 */
  static AffineTransform transform(int orientation, int width, int height) {
    return switch (orientation) {
      case 1 -> new AffineTransform();
      case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
      case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
      case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
      case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
      case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
      case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
      case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
      default -> throw malformed("orientation must be within 1..8");
    };
  }

  private static int parseTiff(byte[] bytes, int base, int available) {
    if (available < 8) {
      throw malformed("EXIF TIFF header is truncated");
    }
    ByteOrder order;
    if (startsWith(bytes, base, available, new byte[] {'I', 'I'})) {
      order = ByteOrder.LITTLE_ENDIAN;
    } else if (startsWith(bytes, base, available, new byte[] {'M', 'M'})) {
      order = ByteOrder.BIG_ENDIAN;
    } else {
      throw malformed("EXIF byte order must be II or MM");
    }
    if (uint16(bytes, base + 2, order) != 42) {
      throw malformed("EXIF TIFF magic must be 42");
    }
    long ifdOffset = uint32(bytes, base + 4, order);
    if (ifdOffset > available - 2) {
      throw malformed("EXIF IFD offset is out of bounds");
    }
    int ifd = base + (int) ifdOffset;
    int count = uint16(bytes, ifd, order);
    for (int index = 0; index < count; index++) {
      int entry = ifd + 2 + index * 12;
      if (entry + 12 > base + available) {
        throw malformed("EXIF IFD entry is truncated");
      }
      if (uint16(bytes, entry, order) != ORIENTATION_TAG) {
        continue;
      }
      if (uint16(bytes, entry + 2, order) != SHORT_TYPE || uint32(bytes, entry + 4, order) != 1L) {
        throw malformed("EXIF orientation must be a single SHORT value");
      }
      int orientation = uint16(bytes, entry + 8, order);
      if (orientation < 1 || orientation > 8) {
        throw malformed("EXIF orientation must be within 1..8");
      }
      return orientation;
    }
    return IDENTITY;
  }

  private static int uint16(byte[] bytes, int offset, ByteOrder order) {
    int first = bytes[offset] & 0xFF;
    int second = bytes[offset + 1] & 0xFF;
    return order == ByteOrder.LITTLE_ENDIAN ? first | (second << 8) : (first << 8) | second;
  }

  private static long uint32(byte[] bytes, int offset, ByteOrder order) {
    long value = 0L;
    for (int index = 0; index < 4; index++) {
      int indexInSegment = order == ByteOrder.LITTLE_ENDIAN ? offset + 3 - index : offset + index;
      value = (value << 8) | (bytes[indexInSegment] & 0xFF);
    }
    return value;
  }

  private static boolean startsWith(byte[] bytes, int offset, int available, byte[] prefix) {
    if (prefix.length > available) {
      return false;
    }
    for (int index = 0; index < prefix.length; index++) {
      if (bytes[offset + index] != prefix[index]) {
        return false;
      }
    }
    return true;
  }

  private static IllegalArgumentException malformed(String reason) {
    return new IllegalArgumentException("EXIF orientation cannot be read: " + reason);
  }
}
