package fun.fengwk.kkstudio.platform.harness.model;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.zip.CRC32;

/**
 * 图片档位物化测试的图片工厂：全部按需生成，避免把二进制夹具固化进仓库。
 *
 * <p>生成的图片左右两半颜色固定（左红右蓝），因此方向归一后的显示布局可以直接用像素断言：旋转后的「上/下」对应旋转前的「左/右」。 {@link
 * #pngWithDeclaredDimensions(int, int)} 只改写 IHDR 并重算 CRC，用于在不真正分配内存的前提下验证解码前的像素预算。
 */
final class TestImages {

  private static final int RED = 0xFFFF0000;
  private static final int BLUE = 0xFF0000FF;

  private TestImages() {}

  /** 不透明 PNG：左半红、右半蓝。 */
  static byte[] png(int width, int height) {
    return encode(split(width, height, false), "png");
  }

  /** 带透明像素的 PNG：左半半透明红、右半不透明蓝。 */
  static byte[] transparentPng(int width, int height) {
    return encode(split(width, height, true), "png");
  }

  /** 不透明 GIF（调色板格式，不携带 alpha 通道）。 */
  static byte[] gif(int width, int height) {
    return encode(split(width, height, false), "gif");
  }

  /** 多帧 GIF：帧数大于 1。 */
  static byte[] animatedGif(int width, int height, int frames) {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
    if (!writers.hasNext()) {
      throw new IllegalStateException("no GIF writer is available");
    }
    ImageWriter writer = writers.next();
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    // ImageOutputStream 必须先关闭（= flush）再读取字节，否则拿到的是被缓冲掉的截断流。
    try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
      writer.setOutput(output);
      writer.prepareWriteSequence(null);
      for (int frame = 0; frame < frames; frame++) {
        writer.writeToSequence(new IIOImage(split(width, height, false), null, null), null);
      }
      writer.endWriteSequence();
    } catch (IOException error) {
      throw new IllegalStateException("cannot encode animated GIF", error);
    } finally {
      writer.dispose();
    }
    return buffer.toByteArray();
  }

  /** 左半红、右半蓝的 JPEG，可选注入 EXIF orientation（1 表示不注入）。 */
  static byte[] jpeg(int width, int height, int orientation) {
    byte[] jpeg = encode(split(width, height, false), "jpeg");
    return orientation == 1 ? jpeg : spliceApp1(jpeg, exifPayload(orientation, false));
  }

  /** 与 {@link #jpeg(int, int, int)} 相同，但用大端 TIFF 头写 EXIF。 */
  static byte[] jpegWithBigEndianExif(int width, int height, int orientation) {
    return spliceApp1(encode(split(width, height, false), "jpeg"), exifPayload(orientation, true));
  }

  /** 注入一个结构非法的 EXIF（字节序不是 II/MM），用于验证「无法解析方向时显式失败」。 */
  static byte[] jpegWithMalformedExif(int width, int height) {
    byte[] payload = {'E', 'x', 'i', 'f', 0, 0, 'X', 'X', 42, 0, 8, 0, 0, 0};
    return spliceApp1(encode(split(width, height, false), "jpeg"), payload);
  }

  /** PNG 头声称指定尺寸、但实际仍是 2x2 的图片：解码前只能看到 IHDR，用于验证像素预算在读取像素之前生效。 */
  static byte[] pngWithDeclaredDimensions(int width, int height) {
    byte[] png = encode(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png");
    writeInt(png, 16, width);
    writeInt(png, 20, height);
    CRC32 crc = new CRC32();
    crc.update(png, 12, 17);
    writeInt(png, 29, (int) crc.getValue());
    return png;
  }

  /** 用声明 MIME 对应的解码器读取图片；测试断言显示尺寸与像素分布。 */
  static BufferedImage decode(String mediaType, byte[] bytes) {
    Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(mediaType);
    if (!readers.hasNext()) {
      throw new IllegalStateException("no reader for " + mediaType);
    }
    ImageReader reader = readers.next();
    try {
      reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)));
      return reader.read(0);
    } catch (IOException error) {
      throw new IllegalStateException("cannot decode " + mediaType, error);
    } finally {
      reader.dispose();
    }
  }

  /** 兼容有损 JPEG 与无损压缩的红色判定。 */
  static boolean isRed(int argb) {
    return ((argb >> 16) & 0xFF) > 0xC0 && ((argb >>> 24) & 0xFF) > 0x80;
  }

  /** 兼容有损 JPEG 与无损压缩的蓝色判定。 */
  static boolean isBlue(int argb) {
    return (argb & 0xFF) > 0xC0 && ((argb >>> 24) & 0xFF) > 0x80;
  }

  private static BufferedImage split(int width, int height, boolean transparent) {
    int type = transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    BufferedImage image = new BufferedImage(width, height, type);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(new Color(transparent ? 0x40FF0000 : RED, true));
      graphics.fillRect(0, 0, width / 2, height);
      graphics.setColor(new Color(BLUE, true));
      graphics.fillRect(width / 2, 0, width - width / 2, height);
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static byte[] encode(BufferedImage image, String format) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      if (!ImageIO.write(image, format, buffer)) {
        throw new IllegalStateException("no " + format + " writer is available");
      }
      return buffer.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("cannot encode " + format, error);
    }
  }

  /** 直接在 SOI 之后插入 APP1 EXIF 段：合法 JPEG 允许扩展段出现在帧头之前。 */
  private static byte[] spliceApp1(byte[] jpeg, byte[] payload) {
    int length = payload.length + 2;
    byte[] result = new byte[jpeg.length + length + 2];
    result[0] = (byte) 0xFF;
    result[1] = (byte) 0xD8;
    result[2] = (byte) 0xFF;
    result[3] = (byte) 0xE1;
    result[4] = (byte) (length >> 8);
    result[5] = (byte) length;
    System.arraycopy(payload, 0, result, 6, payload.length);
    System.arraycopy(jpeg, 2, result, 6 + payload.length, jpeg.length - 2);
    return result;
  }

  /** EXIF payload：{@code "Exif\0\0"} + TIFF 头 + 单个 orientation 标签的 IFD0。 */
  private static byte[] exifPayload(int orientation, boolean bigEndian) {
    byte[] payload = new byte[6 + 8 + 2 + 12 + 4];
    payload[0] = 'E';
    payload[1] = 'x';
    payload[2] = 'i';
    payload[3] = 'f';
    payload[6] = (byte) (bigEndian ? 'M' : 'I');
    payload[7] = payload[6];
    writeShort(payload, 8, 42, bigEndian);
    writeInt(payload, 10, 8, bigEndian);
    writeShort(payload, 14, 1, bigEndian);
    writeShort(payload, 16, 0x0112, bigEndian);
    writeShort(payload, 18, 3, bigEndian);
    writeInt(payload, 20, 1, bigEndian);
    writeShort(payload, 24, orientation, bigEndian);
    writeInt(payload, 28, 0, bigEndian);
    return payload;
  }

  private static void writeInt(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) (value >>> 24);
    bytes[offset + 1] = (byte) (value >>> 16);
    bytes[offset + 2] = (byte) (value >>> 8);
    bytes[offset + 3] = (byte) value;
  }

  private static void writeShort(byte[] bytes, int offset, int value, boolean bigEndian) {
    if (bigEndian) {
      bytes[offset] = (byte) (value >> 8);
      bytes[offset + 1] = (byte) value;
    } else {
      bytes[offset] = (byte) value;
      bytes[offset + 1] = (byte) (value >> 8);
    }
  }

  private static void writeInt(byte[] bytes, int offset, int value, boolean bigEndian) {
    if (bigEndian) {
      writeInt(bytes, offset, value);
      return;
    }
    bytes[offset] = (byte) value;
    bytes[offset + 1] = (byte) (value >>> 8);
    bytes[offset + 2] = (byte) (value >>> 16);
    bytes[offset + 3] = (byte) (value >>> 24);
  }
}
