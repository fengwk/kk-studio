package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ImageInputTier;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * {@link ProviderImageScaler} 的图片档位物化契约：方向、比例、原字节直通与显式失败。
 *
 * <p>测试意图概览：
 *
 * <ul>
 *   <li>横向 1280x720、纵向 720x1280、正方形由短边 720 界定的档位框，且只缩小不放大、不裁剪、不拉伸；
 *   <li>命中档位框的图片逐字节保留原内容（即使携带 EXIF），解码与重编码绝不发生；
 *   <li>JPEG 的 EXIF orientation 1/3/6/8 必须按显示方向归一（用左右两半的红/蓝像素验证方向，而不是只看尺寸）；
 *   <li>WebP（TwelveMonkeys 解码）与 PNG/GIF/JPEG 一样可解析、可缩放，样例来自真实 VP8 编码资源；
 *   <li>含 alpha 的产物无损 PNG，不含 alpha 的 JPEG 源保持 JPEG、其余源无损 PNG；
 *   <li>所有档位（含 ORIGINAL 与命中档位框）都先执行同一套头校验：声明 MIME 无解码器、字节与 MIME 不一致、超出解码像素预算时显式失败；动画/多帧在需要缩放时显式失败。
 * </ul>
 */
class ProviderImageScalerTest {

  private static final long GENEROUS_BYTES = 64L * 1024L * 1024L;

  // ---------- 档位框与方向 ----------

  @Test
  void landscapeImageIsBoundedByTheLongEdge() {
    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale(
            "image/png", TestImages.png(2000, 1000), ImageInputTier.P720, GENEROUS_BYTES);

    assertEquals("image/png", scaled.mediaType());
    BufferedImage image = TestImages.decode(scaled.mediaType(), scaled.bytes());
    // 2000x1000 等比缩小到 1280x640：长边贴合 1280，未裁剪也未拉伸。
    assertEquals(1280, image.getWidth());
    assertEquals(640, image.getHeight());
    assertTrue(TestImages.isRed(image.getRGB(image.getWidth() / 4, image.getHeight() / 2)));
    assertTrue(TestImages.isBlue(image.getRGB(image.getWidth() * 3 / 4, image.getHeight() / 2)));
  }

  @Test
  void portraitImageIsBoundedBySwappedEdges() {
    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale(
            "image/png", TestImages.png(1000, 2000), ImageInputTier.P720, GENEROUS_BYTES);

    BufferedImage image = TestImages.decode(scaled.mediaType(), scaled.bytes());
    assertEquals(640, image.getWidth());
    assertEquals(1280, image.getHeight());
  }

  @Test
  void squareImageIsBoundedByTheShorterEdge() {
    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale(
            "image/png", TestImages.png(2000, 2000), ImageInputTier.P720, GENEROUS_BYTES);

    BufferedImage image = TestImages.decode(scaled.mediaType(), scaled.bytes());
    assertEquals(720, image.getWidth());
    assertEquals(720, image.getHeight());
  }

  @Test
  void fullHdTierUsesItsOwnBoxes() {
    BufferedImage landscape =
        TestImages.decode(
            "image/png",
            ProviderImageScaler.scale(
                    "image/png", TestImages.png(4000, 2000), ImageInputTier.P1080, GENEROUS_BYTES)
                .bytes());
    assertEquals(1920, landscape.getWidth());
    assertEquals(960, landscape.getHeight());

    BufferedImage square =
        TestImages.decode(
            "image/png",
            ProviderImageScaler.scale(
                    "image/png", TestImages.png(4000, 4000), ImageInputTier.P1080, GENEROUS_BYTES)
                .bytes());
    assertEquals(1080, square.getWidth());
    assertEquals(1080, square.getHeight());
  }

  // ---------- 原字节直通 ----------

  @Test
  void imageWithinTierBoxKeepsOriginalBytesEvenWithExifPresent() {
    // 1000x500 的 stored 尺寸在横向上超出 720 的高，但显示方向（EXIF 6）下是 500x1000，落在 720x1280 内。
    byte[] jpeg = TestImages.jpeg(1000, 500, 6);

    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale("image/jpeg", jpeg, ImageInputTier.P720, GENEROUS_BYTES);

    assertEquals("image/jpeg", scaled.mediaType());
    assertArrayEquals(jpeg, scaled.bytes(), "档位框内必须保留原始字节（含 EXIF）");
  }

  @Test
  void originalTierAlwaysReturnsOriginalBytes() {
    byte[] jpeg = TestImages.jpeg(4000, 2000, 3);

    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale("image/jpeg", jpeg, ImageInputTier.ORIGINAL, GENEROUS_BYTES);

    assertEquals("image/jpeg", scaled.mediaType());
    assertArrayEquals(jpeg, scaled.bytes());
  }

  @Test
  void originalTierStillValidatesFormatHeaderAndPixelBudget() {
    // ORIGINAL 不重新编码，但与缩放档位执行同一套格式与像素校验：假 WebP 头必须显式失败。
    byte[] webpLike = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProviderImageScaler.scale(
                    "image/webp", webpLike, ImageInputTier.ORIGINAL, GENEROUS_BYTES));

    assertTrue(error.getMessage().contains("cannot be decoded"), error.getMessage());
  }

  @Test
  void originalTierRejectsOversizedPixelBudgetBeforeDecoding() {
    byte[] hugeHeader = TestImages.pngWithDeclaredDimensions(20_000, 20_000);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProviderImageScaler.scale(
                    "image/png", hugeHeader, ImageInputTier.ORIGINAL, GENEROUS_BYTES));

    assertTrue(error.getMessage().contains("pixel budget"), error.getMessage());
  }

  // ---------- EXIF 方向 ----------

  /** 方向 6 = 顺时针 90 度：stored 左半（红）成为显示的上一半。 */
  @Test
  void exifOrientationSixRotatesClockwise() {
    BufferedImage image = resizedJpeg(2000, 1000, 6);

    // stored 2000x1000 旋转后显示 1000x2000（纵向），缩放比例 0.64 -> 640x1280。
    assertEquals(640, image.getWidth());
    assertEquals(1280, image.getHeight());
    assertTrue(TestImages.isRed(image.getRGB(image.getWidth() / 2, image.getHeight() / 4)));
    assertTrue(TestImages.isBlue(image.getRGB(image.getWidth() / 2, image.getHeight() * 3 / 4)));
  }

  /** 方向 8 = 逆时针 90 度：stored 右半（蓝）成为显示的上一半。 */
  @Test
  void exifOrientationEightRotatesCounterClockwise() {
    BufferedImage image = resizedJpeg(2000, 1000, 8);

    assertEquals(640, image.getWidth());
    assertEquals(1280, image.getHeight());
    assertTrue(TestImages.isBlue(image.getRGB(image.getWidth() / 2, image.getHeight() / 4)));
    assertTrue(TestImages.isRed(image.getRGB(image.getWidth() / 2, image.getHeight() * 3 / 4)));
  }

  /** 方向 3 = 180 度：stored 左半（红）成为显示的右半。 */
  @Test
  void exifOrientationThreeRotatesHalfTurn() {
    BufferedImage image = resizedJpeg(2000, 1000, 3);

    assertEquals(1280, image.getWidth());
    assertEquals(640, image.getHeight());
    assertTrue(TestImages.isBlue(image.getRGB(image.getWidth() / 4, image.getHeight() / 2)));
    assertTrue(TestImages.isRed(image.getRGB(image.getWidth() * 3 / 4, image.getHeight() / 2)));
  }

  @Test
  void exifOrientationOneKeepsOriginalLayout() {
    BufferedImage image = resizedJpeg(2000, 1000, 1);

    assertEquals(1280, image.getWidth());
    assertEquals(640, image.getHeight());
    assertTrue(TestImages.isRed(image.getRGB(image.getWidth() / 4, image.getHeight() / 2)));
    assertTrue(TestImages.isBlue(image.getRGB(image.getWidth() * 3 / 4, image.getHeight() / 2)));
  }

  @Test
  void bigEndianExifOrientationIsHonoured() {
    byte[] jpeg = TestImages.jpegWithBigEndianExif(2000, 1000, 6);

    BufferedImage image =
        TestImages.decode(
            "image/jpeg",
            ProviderImageScaler.scale("image/jpeg", jpeg, ImageInputTier.P720, GENEROUS_BYTES)
                .bytes());

    assertEquals(640, image.getWidth());
    assertEquals(1280, image.getHeight());
    assertTrue(TestImages.isRed(image.getRGB(image.getWidth() / 2, image.getHeight() / 4)));
  }

  @Test
  void malformedExifFailsExplicitlyInsteadOfGuessingOrientation() {
    byte[] jpeg = TestImages.jpegWithMalformedExif(2000, 1000);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProviderImageScaler.scale("image/jpeg", jpeg, ImageInputTier.P720, GENEROUS_BYTES));

    assertTrue(error.getMessage().contains("EXIF"), error.getMessage());
  }

  // ---------- 编码选择 ----------

  @Test
  void transparentImageIsLosslesslyEncodedAsPng() {
    byte[] png = TestImages.transparentPng(2000, 1000);

    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale("image/png", png, ImageInputTier.P720, GENEROUS_BYTES);

    assertEquals("image/png", scaled.mediaType());
    BufferedImage image = TestImages.decode(scaled.mediaType(), scaled.bytes());
    assertEquals(1280, image.getWidth());
    // 半透明红色区域必须仍然是半透明：alpha 不得被丢弃或压成不透明黑底。
    assertTrue(((image.getRGB(image.getWidth() / 4, image.getHeight() / 2) >>> 24) & 0xFF) < 0xFF);
  }

  @Test
  void opaqueJpegSourceIsReencodedAsJpeg() {
    byte[] jpeg = TestImages.jpeg(2000, 1000, 1);

    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale("image/jpeg", jpeg, ImageInputTier.P720, GENEROUS_BYTES);

    assertEquals("image/jpeg", scaled.mediaType());
  }

  @Test
  void opaqueNonJpegSourceIsReencodedAsPng() {
    // 截屏类 GIF（调色板、无不透明 alpha 通道）在缩小时无损转为 PNG，避免有损重编码文本。
    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale(
            "image/gif", TestImages.gif(2000, 1000), ImageInputTier.P720, GENEROUS_BYTES);

    assertEquals("image/png", scaled.mediaType());
    assertEquals(1280, TestImages.decode("image/png", scaled.bytes()).getWidth());
  }

  // ---------- WebP ----------

  /** WebP 缩放样例：真实 VP8 编码的 2000x1000 红色图片（超出 720P 与 1080P 档位框）。 */
  private static byte[] webpSample() throws IOException {
    try (InputStream input =
        ProviderImageScalerTest.class.getResourceAsStream("images/red-2000x1000.webp")) {
      Objects.requireNonNull(input, "missing test resource images/red-2000x1000.webp");
      return input.readAllBytes();
    }
  }

  /** WebP 直通样例：真实 VP8 编码的 1800x900 红色图片（落在 1080P 档位框内）。 */
  private static byte[] webpWithinBoxSample() throws IOException {
    try (InputStream input =
        ProviderImageScalerTest.class.getResourceAsStream("images/red-1800x900.webp")) {
      Objects.requireNonNull(input, "missing test resource images/red-1800x900.webp");
      return input.readAllBytes();
    }
  }

  @Test
  void webpIsDecodedAndResizedWithinTheTierBox() throws IOException {
    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale("image/webp", webpSample(), ImageInputTier.P720, GENEROUS_BYTES);

    // VP8 是 YUV 有损编码且无 alpha：按契约输出为 PNG。
    assertEquals("image/png", scaled.mediaType());
    BufferedImage image = TestImages.decode(scaled.mediaType(), scaled.bytes());
    assertEquals(1280, image.getWidth());
    assertEquals(640, image.getHeight());
    assertTrue(TestImages.isRed(image.getRGB(image.getWidth() / 4, image.getHeight() / 2)));
  }

  @Test
  void webpWithinTierBoxKeepsOriginalBytes() throws IOException {
    byte[] webp = webpWithinBoxSample();

    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale("image/webp", webp, ImageInputTier.P1080, GENEROUS_BYTES);

    assertArrayEquals(webp, scaled.bytes());
    assertEquals("image/webp", scaled.mediaType());
  }

  @Test
  void webpOriginalTierValidatesThenKeepsOriginalBytes() throws IOException {
    byte[] webp = webpSample();

    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale("image/webp", webp, ImageInputTier.ORIGINAL, GENEROUS_BYTES);

    assertArrayEquals(webp, scaled.bytes());
  }

  @Test
  void animatedWebpIsRejectedWhenResizing() throws IOException {
    byte[] animated;
    try (InputStream input =
        ProviderImageScalerTest.class.getResourceAsStream("images/animated-2000x1000.webp")) {
      Objects.requireNonNull(input, "missing test resource images/animated-2000x1000.webp");
      animated = input.readAllBytes();
    }

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProviderImageScaler.scale(
                    "image/webp", animated, ImageInputTier.P720, GENEROUS_BYTES));

    assertTrue(error.getMessage().contains("multi-frame"), error.getMessage());
  }

  // ---------- 显式失败 ----------

  @Test
  void animatedImageIsRejectedInsteadOfTakingTheFirstFrame() {
    byte[] animated = TestImages.animatedGif(2000, 1000, 3);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProviderImageScaler.scale(
                    "image/gif", animated, ImageInputTier.P720, GENEROUS_BYTES));

    assertTrue(error.getMessage().contains("multi-frame"), error.getMessage());
  }

  @Test
  void animatedImageWithinTierBoxIsPassedThroughUnchanged() {
    // 未触发缩放时不会选帧：原字节直通，动画语义由 Provider 自行处理。
    byte[] animated = TestImages.animatedGif(200, 100, 3);

    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale("image/gif", animated, ImageInputTier.P720, GENEROUS_BYTES);

    assertArrayEquals(animated, scaled.bytes());
  }

  @Test
  void unsupportedMediaTypeFailsExplicitly() {
    // image/x-icon 没有 JDK 或 TwelveMonkeys 解码器：缩放档位必须显式失败。
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProviderImageScaler.scale(
                    "image/x-icon",
                    TestImages.png(2000, 1000),
                    ImageInputTier.P720,
                    GENEROUS_BYTES));

    assertTrue(error.getMessage().contains("unsupported image media type"), error.getMessage());
  }

  @Test
  void declaredMediaTypeAndContentMismatchFailsExplicitly() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProviderImageScaler.scale(
                    "image/jpeg", TestImages.png(2000, 1000), ImageInputTier.P720, GENEROUS_BYTES));

    assertTrue(error.getMessage().contains("cannot be decoded"), error.getMessage());
  }

  @Test
  void decodePixelBudgetIsEnforcedBeforeReadingPixels() {
    byte[] hugeHeader = TestImages.pngWithDeclaredDimensions(20_000, 20_000);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProviderImageScaler.scale(
                    "image/png", hugeHeader, ImageInputTier.P720, GENEROUS_BYTES));

    assertTrue(error.getMessage().contains("pixel budget"), error.getMessage());
  }

  @Test
  void outputByteBudgetIsEnforced() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProviderImageScaler.scale(
                    "image/png", TestImages.png(2000, 1000), ImageInputTier.P720, 64L));

    assertTrue(error.getMessage().contains("resized image exceeds"), error.getMessage());
  }

  private static BufferedImage resizedJpeg(int width, int height, int orientation) {
    ProviderImageScaler.Scaled scaled =
        ProviderImageScaler.scale(
            "image/jpeg",
            TestImages.jpeg(width, height, orientation),
            ImageInputTier.P720,
            GENEROUS_BYTES);
    assertEquals("image/jpeg", scaled.mediaType());
    return TestImages.decode(scaled.mediaType(), scaled.bytes());
  }
}
