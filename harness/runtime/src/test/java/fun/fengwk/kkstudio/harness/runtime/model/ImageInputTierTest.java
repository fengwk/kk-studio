package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ImageInputTier} 的 wire/尺寸契约：wire 与 durable 只使用规范名称，档位框按方向确定。
 *
 * <p>测试意图：档位名称是 durable 与 wire 的公共事实，枚举名（{@code P720}）或任何未知名称都不得被接受；档位框决定了真正送达的像素上限，
 * 因此三个档位的长短边都必须与文档一致，并且只有 {@code ORIGINAL} 跳过变换。
 */
class ImageInputTierTest {

  @Test
  void wireNamesAreTheContractNamesNotEnumNames() {
    assertEquals("720P", ImageInputTier.P720.wireName());
    assertEquals("1080P", ImageInputTier.P1080.wireName());
    assertEquals("ORIGINAL", ImageInputTier.ORIGINAL.wireName());
  }

  @Test
  void fromWireNameRoundTripsEveryValue() {
    for (ImageInputTier tier : ImageInputTier.values()) {
      assertEquals(tier, ImageInputTier.fromWireName(tier.wireName()));
    }
  }

  @Test
  void fromWireNameRejectsEnumNamesUnknownNamesAndNull() {
    // 枚举名不是 wire 名称：接受它会让 wire 契约与 durable 事实不一致。
    assertThrows(IllegalArgumentException.class, () -> ImageInputTier.fromWireName("P720"));
    assertThrows(IllegalArgumentException.class, () -> ImageInputTier.fromWireName("720p"));
    assertThrows(IllegalArgumentException.class, () -> ImageInputTier.fromWireName("4K"));
    assertThrows(IllegalArgumentException.class, () -> ImageInputTier.fromWireName(""));
    assertThrows(IllegalArgumentException.class, () -> ImageInputTier.fromWireName(null));
  }

  @Test
  void boundsDefineTheDirectionAwareBoxPerTier() {
    // 720P：横向最长边 1280、纵向最长边 720，正方形由较短的 720 界定。
    assertEquals(1280, ImageInputTier.P720.maxLongEdge());
    assertEquals(720, ImageInputTier.P720.maxShortEdge());
    // 1080P：横向最长边 1920、纵向最长边 1080。
    assertEquals(1920, ImageInputTier.P1080.maxLongEdge());
    assertEquals(1080, ImageInputTier.P1080.maxShortEdge());
  }

  @Test
  void onlyOriginalSkipsTransformation() {
    assertTrue(ImageInputTier.ORIGINAL.isOriginal());
    assertFalse(ImageInputTier.P720.isOriginal());
    assertFalse(ImageInputTier.P1080.isOriginal());
  }
}
