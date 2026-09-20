package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** region 的固定 origin、登录链接与 deep-link scheme 映射。 */
class MavisRegionTest {

  /** 两个 region 的 origin、登录链接与 scheme 是固定协议事实，不接受自定义 base URL。 */
  @Test
  void fixesOfficialOriginsAndLoginUrls() {
    assertEquals("https://agent.minimaxi.com", MavisRegion.CN.baseUrl());
    assertEquals(
        "https://agent.minimaxi.com/login?sso=1&download_source=default",
        MavisRegion.CN.loginUrl());
    assertEquals("minimax-cn", MavisRegion.CN.callbackScheme());

    assertEquals("https://agent.minimax.io", MavisRegion.EN.baseUrl());
    assertEquals(
        "https://agent.minimax.io/login?sso=1&download_source=default", MavisRegion.EN.loginUrl());
    assertEquals("minimax", MavisRegion.EN.callbackScheme());
  }

  /** region 标识严格按 cn/en 解析，大小写与空白被归一化，其他取值不回退。 */
  @Test
  void parsesOnlySupportedRegionIds() {
    assertEquals(MavisRegion.CN, MavisRegion.parse(" CN "));
    assertEquals(MavisRegion.EN, MavisRegion.parse("en"));
    assertThrows(MavisValidationException.class, () -> MavisRegion.parse("global"));
    assertThrows(MavisValidationException.class, () -> MavisRegion.parse(""));
    assertThrows(MavisValidationException.class, () -> MavisRegion.parse(null));
  }

  /** deep-link scheme 决定 region；未知 scheme 返回空。 */
  @Test
  void mapsCallbackSchemeToRegion() {
    assertEquals(MavisRegion.CN, MavisRegion.fromCallbackScheme("MINIMAX-CN").orElseThrow());
    assertEquals(MavisRegion.EN, MavisRegion.fromCallbackScheme("minimax").orElseThrow());
    assertTrue(MavisRegion.fromCallbackScheme("minimax-us").isEmpty());
    assertTrue(MavisRegion.fromCallbackScheme(null).isEmpty());
  }
}
