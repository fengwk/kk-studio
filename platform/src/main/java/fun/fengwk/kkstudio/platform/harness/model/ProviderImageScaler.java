package fun.fengwk.kkstudio.platform.harness.model;

import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;

/**
 * 图片输入档位的 attempt-only 缩放：把超出档位框的图片按显示方向等比缩小，命中档位框的图片保留原字节。
 *
 * <p>本类只处理已经是权威 ACTIVE Blob 的内容，产物只用于当前 attempt 的瞬时 data URI，绝不写回任何存储（无派生 Blob）。失败一律显式，绝不静默降级：
 *
 * <ul>
 *   <li>声明 MIME 无可用解码器（webp/heic 等 JDK 不支持的格式）或字节与声明 MIME 不一致时失败；
 *   <li>解码前先用图片头校验宽高，超过 {@link #MAX_DECODED_PIXELS} 的像素预算立即失败，避免解压炸弹；
 *   <li>动画或多帧图片（如多帧 GIF）失败，绝不只取第一帧；
 *   <li>JPEG 按 EXIF orientation 归一化显示方向；EXIF 段存在但不可解析时失败，绝不静默按未旋转处理。
 * </ul>
 *
 * <p>方向归一后按档位框等比缩小：横向使用档位的最长边、纵向交换长短边、正方形由短边界定；只缩小不放大，也不裁剪或拉伸。含非全不透明像素的产物 无损编码为 PNG，不含 alpha 的 JPEG
 * 源按高质量 JPEG 重编码，其余不含 alpha 的源编码为 PNG（截屏与文本类图片无损）。
 */
final class ProviderImageScaler {

  /** 解码前允许的最大像素数（宽 × 高）：超出即显式失败。 */
  static final long MAX_DECODED_PIXELS = 40_000_000L;

  /** 无 alpha 的 JPEG 重编码质量：接近视觉无损，同时显著小于原图。 */
  private static final float JPEG_QUALITY = 0.92f;

  private static final String JPEG = "image/jpeg";
  private static final String PNG = "image/png";

  private ProviderImageScaler() {}

  /** 缩放产物：{@code mediaType} 与编码格式严格一致，用于 data URI header。 */
  record Scaled(String mediaType, byte[] bytes) {

    Scaled {
      Objects.requireNonNull(mediaType, "mediaType");
      Objects.requireNonNull(bytes, "bytes");
    }
  }

  /** 方向归一后的档位框：正方形由短边界定。 */
  private record Box(int maxWidth, int maxHeight) {}

  /**
   * 按 {@code tier} 物化 {@code bytes}：命中档位框时逐字节返回原内容（保留原始编码与 EXIF），超出时缩小重新编码。
   *
   * @param mediaType 权威 MIME（解码器与 data URI header 都以此为准）
   * @param bytes 权威原始字节
   * @param tier 目标档位；{@link ImageInputTier#ORIGINAL} 表示永不转换
   * @param maxOutputBytes 重新编码后的字节上限，超出显式失败
   * @throws IllegalArgumentException 格式不支持、内容不可解析、超出像素预算或输出字节预算时抛出
   */
  static Scaled scale(String mediaType, byte[] bytes, ImageInputTier tier, long maxOutputBytes) {
    Objects.requireNonNull(mediaType, "mediaType");
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(tier, "tier");
    if (tier.isOriginal()) {
      return new Scaled(mediaType, bytes);
    }
    int orientation = JpegExifOrientation.read(mediaType, bytes);
    try (ImageInputStream stream =
        ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (stream == null) {
        throw new IllegalArgumentException("image content of " + mediaType + " cannot be read");
      }
      ImageReader reader = firstReader(mediaType);
      try {
        reader.setInput(stream, false, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0 || pixels > MAX_DECODED_PIXELS) {
          throw new IllegalArgumentException(
              "image of "
                  + width
                  + "x"
                  + height
                  + " pixels exceeds the decodable pixel budget "
                  + MAX_DECODED_PIXELS);
        }
        int displayWidth = JpegExifOrientation.swapsAxes(orientation) ? height : width;
        int displayHeight = JpegExifOrientation.swapsAxes(orientation) ? width : height;
        Box box = box(tier, displayWidth, displayHeight);
        if (displayWidth <= box.maxWidth() && displayHeight <= box.maxHeight()) {
          return new Scaled(mediaType, bytes);
        }
        // 只有在确实需要缩放时才枚举帧：命中档位框的图片逐字节直通，不存在「取第一帧」的语义损失。
        int frames = reader.getNumImages(true);
        if (frames > 1) {
          throw new IllegalArgumentException(
              "multi-frame image with "
                  + frames
                  + " frames must not be resized to "
                  + tier.wireName());
        }
        BufferedImage decoded = reader.read(0);
        if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
          throw new IllegalArgumentException(
              "image content of " + mediaType + " does not match its declared dimensions");
        }
        double factor =
            Math.min(
                (double) box.maxWidth() / displayWidth, (double) box.maxHeight() / displayHeight);
        int targetWidth = clamp(box.maxWidth(), displayWidth * factor);
        int targetHeight = clamp(box.maxHeight(), displayHeight * factor);
        BufferedImage resized =
            resize(
                decoded,
                JpegExifOrientation.transform(orientation, width, height),
                displayWidth,
                displayHeight,
                targetWidth,
                targetHeight);
        return encode(resized, mediaType, maxOutputBytes);
      } finally {
        reader.dispose();
      }
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "image content declared as " + mediaType + " cannot be decoded", error);
    }
  }

  /** 档位框：横向用最长边、纵向交换长短边、正方形由短边界定。 */
  private static Box box(ImageInputTier tier, int displayWidth, int displayHeight) {
    int longEdge = tier.maxLongEdge();
    int shortEdge = tier.maxShortEdge();
    if (displayWidth > displayHeight) {
      return new Box(longEdge, shortEdge);
    }
    if (displayHeight > displayWidth) {
      return new Box(shortEdge, longEdge);
    }
    return new Box(shortEdge, shortEdge);
  }

  private static int clamp(int max, double value) {
    return Math.min(max, Math.max(1, (int) Math.round(value)));
  }

  private static ImageReader firstReader(String mediaType) {
    Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(mediaType);
    if (!readers.hasNext()) {
      throw new IllegalArgumentException("unsupported image media type for resizing: " + mediaType);
    }
    return readers.next();
  }

  /**
   * 按显示方向缩小 {@code source}：每步最多缩小 2 倍，避免单次双线性采样在大幅缩小时混叠。
   *
   * <p>{@code orientation} 只在第一步使用（把源坐标映射到显示坐标）；之后图像已经是显示方向，只做纯比例缩小。
   */
  private static BufferedImage resize(
      BufferedImage source,
      AffineTransform orientation,
      int displayWidth,
      int displayHeight,
      int targetWidth,
      int targetHeight) {
    double step = 1.0;
    while (step * 2.0
        <= (double) Math.max(displayWidth, displayHeight) / Math.max(targetWidth, targetHeight)) {
      step *= 2.0;
    }
    if (step > 1.0) {
      int stepWidth = Math.max(1, (int) Math.round(displayWidth / step));
      int stepHeight = Math.max(1, (int) Math.round(displayHeight / step));
      source = draw(source, orientation, stepWidth, stepHeight, displayWidth, displayHeight);
      orientation = null;
      displayWidth = stepWidth;
      displayHeight = stepHeight;
    }
    return draw(source, orientation, targetWidth, targetHeight, displayWidth, displayHeight);
  }

  /** 把 {@code source} 绘制到目标尺寸：{@code orientation} 非空时先把源坐标映射到显示坐标，再等比缩放到目标尺寸。 */
  private static BufferedImage draw(
      BufferedImage source,
      AffineTransform orientation,
      int targetWidth,
      int targetHeight,
      int displayWidth,
      int displayHeight) {
    int type =
        source.getColorModel().hasAlpha()
            ? BufferedImage.TYPE_INT_ARGB
            : BufferedImage.TYPE_INT_RGB;
    BufferedImage target = new BufferedImage(targetWidth, targetHeight, type);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      AffineTransform scale =
          AffineTransform.getScaleInstance(
              (double) targetWidth / displayWidth, (double) targetHeight / displayHeight);
      if (orientation != null) {
        scale.concatenate(orientation);
      }
      graphics.drawImage(source, scale, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  /** 含 alpha 的产物必须无损 PNG；不含 alpha 时 JPEG 源保持 JPEG，其余源无损 PNG。 */
  private static Scaled encode(BufferedImage image, String sourceMediaType, long maxOutputBytes) {
    byte[] bytes;
    String mediaType;
    if (hasAlpha(image)) {
      mediaType = PNG;
      bytes = encodePng(image);
    } else {
      BufferedImage opaque = toOpaque(image);
      if (JPEG.equals(sourceMediaType)) {
        mediaType = JPEG;
        bytes = encodeJpeg(opaque);
      } else {
        mediaType = PNG;
        bytes = encodePng(opaque);
      }
    }
    if (bytes.length > maxOutputBytes) {
      throw new IllegalArgumentException(
          "resized image exceeds " + maxOutputBytes + " bytes (" + bytes.length + " bytes)");
    }
    return new Scaled(mediaType, bytes);
  }

  /** 像素级 alpha 判定：存在任一非全不透明像素即需要无损 PNG。 */
  private static boolean hasAlpha(BufferedImage image) {
    if (!image.getColorModel().hasAlpha()) {
      return false;
    }
    int width = image.getWidth();
    int[] row = new int[width];
    for (int y = 0; y < image.getHeight(); y++) {
      image.getRGB(0, y, width, 1, row, 0, width);
      for (int x = 0; x < width; x++) {
        if ((row[x] >>> 24) != 0xFF) {
          return true;
        }
      }
    }
    return false;
  }

  /** 全不透明像素丢弃 alpha 通道：JPEG 无法编码 alpha，PNG 也不需要多余的通道。 */
  private static BufferedImage toOpaque(BufferedImage image) {
    if (image.getType() == BufferedImage.TYPE_INT_RGB) {
      return image;
    }
    BufferedImage opaque =
        new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = opaque.createGraphics();
    try {
      graphics.drawImage(image, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    return opaque;
  }

  private static byte[] encodePng(BufferedImage image) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      if (!ImageIO.write(image, "png", buffer)) {
        throw new IllegalStateException("no PNG writer is available");
      }
      return buffer.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("cannot encode resized image as PNG", error);
    }
  }

  private static byte[] encodeJpeg(BufferedImage image) {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(JPEG);
    if (!writers.hasNext()) {
      throw new IllegalStateException("no JPEG writer is available");
    }
    ImageWriter writer = writers.next();
    try {
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(JPEG_QUALITY);
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
        writer.setOutput(output);
        writer.write(null, new IIOImage(image, null, null), param);
      }
      return buffer.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("cannot encode resized image as JPEG", error);
    } finally {
      writer.dispose();
    }
  }
}
