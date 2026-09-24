package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

/**
 * {@link JpegExifOrientation} 的解析契约：只有可确认的方向才被接受，其余情况要么按 1（无需变换）处理，要么显式失败。
 *
 * <p>测试意图：方向决定缩放框（纵向/横向）与像素排布，一旦解析错误就会把图片按错误方向送给 Provider； 因此「非 JPEG / 无 EXIF」必须视为无方向信息，而「有 EXIF
 * 但结构非法或取值非法」必须显式失败而不是猜测。
 */
class JpegExifOrientationTest {

  @Test
  void nonJpegContentCarriesNoOrientation() {
    assertEquals(1, JpegExifOrientation.read("image/png", TestImages.png(20, 10)));
  }

  @Test
  void jpegWithoutExifCarriesNoOrientation() {
    assertEquals(1, JpegExifOrientation.read("image/jpeg", TestImages.jpeg(20, 10, 1)));
  }

  @Test
  void littleEndianExifOrientationIsParsed() {
    for (int orientation = 1; orientation <= 8; orientation++) {
      assertEquals(
          orientation,
          JpegExifOrientation.read("image/jpeg", TestImages.jpeg(20, 10, orientation)));
    }
  }

  @Test
  void bigEndianExifOrientationIsParsed() {
    assertEquals(
        6, JpegExifOrientation.read("image/jpeg", TestImages.jpegWithBigEndianExif(20, 10, 6)));
  }

  @Test
  void malformedExifFailsInsteadOfFallingBackToIdentity() {
    byte[] jpeg = TestImages.jpegWithMalformedExif(20, 10);

    assertThrows(
        IllegalArgumentException.class, () -> JpegExifOrientation.read("image/jpeg", jpeg));
  }

  @Test
  void outOfRangeOrientationFails() {
    assertThrows(
        IllegalArgumentException.class,
        () -> JpegExifOrientation.read("image/jpeg", TestImages.jpeg(20, 10, 9)));
  }

  @Test
  void axisSwapFollowsOrientation() {
    assertFalse(JpegExifOrientation.swapsAxes(1));
    assertFalse(JpegExifOrientation.swapsAxes(2));
    assertFalse(JpegExifOrientation.swapsAxes(3));
    assertFalse(JpegExifOrientation.swapsAxes(4));
    assertTrue(JpegExifOrientation.swapsAxes(5));
    assertTrue(JpegExifOrientation.swapsAxes(6));
    assertTrue(JpegExifOrientation.swapsAxes(7));
    assertTrue(JpegExifOrientation.swapsAxes(8));
  }

  @Test
  void transformOfOrientationOneIsIdentity() {
    assertTrue(JpegExifOrientation.transform(1, 20, 10).isIdentity());
  }

  @Test
  void mirroringOrientationFlipsHorizontally() {
    // 方向 2 = 水平镜像：x 轴翻转并平移一个宽度，尺寸不变。
    AffineTransform transform = JpegExifOrientation.transform(2, 20, 10);
    assertEquals(-1, transform.getScaleX());
    assertEquals(1, transform.getScaleY());
    assertEquals(20, transform.getTranslateX());
    assertEquals(new Point2D.Double(20, 0), transform.transform(new Point2D.Double(0, 0), null));
  }

  @Test
  void quarterTurnMapsSourceCornersToDisplayCorners() {
    assertEquals(
        new Point2D.Double(10, 0),
        JpegExifOrientation.transform(6, 20, 10).transform(new Point2D.Double(0, 0), null));
    assertEquals(
        new Point2D.Double(0, 20),
        JpegExifOrientation.transform(8, 20, 10).transform(new Point2D.Double(0, 0), null));
    assertEquals(
        new Point2D.Double(20, 10),
        JpegExifOrientation.transform(3, 20, 10).transform(new Point2D.Double(0, 0), null));
  }
}
