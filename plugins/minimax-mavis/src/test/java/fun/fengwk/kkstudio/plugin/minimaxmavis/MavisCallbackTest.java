package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * deep-link 回调解析的边界与去敏。
 *
 * <p>回调 URL 直接携带 access token，因此这里的每个非法输入都必须在发出网络请求前失败，且异常与 toString 都不能回显原文。
 */
class MavisCallbackTest {

  private static final String TOKEN = "access.token.value";

  /** 合法 CN 回调解析出 token 与 CN region。 */
  @Test
  void parsesCnCallback() {
    MavisCallback callback = MavisCallback.parse("minimax-cn://auth-callback?accessToken=" + TOKEN);

    assertEquals(TOKEN, callback.accessToken());
    assertEquals(MavisRegion.CN, callback.region());
  }

  /** EN 回调与大小写不同的 scheme 都映射到 EN region；authority 仍需精确匹配。 */
  @Test
  void parsesEnCallbackCaseInsensitively() {
    MavisCallback callback = MavisCallback.parse("MINIMAX://auth-callback?accessToken=" + TOKEN);

    assertEquals(MavisRegion.EN, callback.region());
  }

  /** form 编码的 token（空格为 +，其余为百分号转义）按 UTF-8 还原。 */
  @Test
  void decodesFormEncodedToken() {
    MavisCallback callback =
        MavisCallback.parse("minimax-cn://auth-callback?accessToken=a%2Bb+c%20d%E4%B8%AD");

    assertEquals("a+b c d中", callback.accessToken());
  }

  /** 回调显式报告 error 时按认证失败处理，且不回显 error 原文。 */
  @Test
  void reportsCallbackError() {
    MavisAuthException error =
        assertThrows(
            MavisAuthException.class,
            () ->
                MavisCallback.parse(
                    "minimax-cn://auth-callback?error=" + TOKEN + "&accessToken=" + TOKEN));

    assertTrue(error.getMessage().contains("reported an error"));
    assertFalse(error.getMessage().contains(TOKEN));
  }

  /** 缺失、重复或为空的 accessToken 都必须被拒绝。 */
  @Test
  void rejectsMissingDuplicateOrEmptyToken() {
    assertThrows(
        MavisValidationException.class, () -> MavisCallback.parse("minimax-cn://auth-callback"));
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("minimax-cn://auth-callback?accessToken="));
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("minimax-cn://auth-callback?accessToken=a&accessToken=b"));
  }

  /** scheme、authority、path 与 fragment 都必须精确匹配预期形态。 */
  @Test
  void rejectsUnexpectedCallbackShape() {
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("https://auth-callback?accessToken=" + TOKEN));
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("minimax-cn://other-host?accessToken=" + TOKEN));
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("minimax-cn://auth-callback/extra?accessToken=" + TOKEN));
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("minimax-cn://auth-callback?accessToken=" + TOKEN + "#frag"));
    assertThrows(MavisValidationException.class, () -> MavisCallback.parse("minimax-cn://"));
    assertThrows(MavisValidationException.class, () -> MavisCallback.parse("   "));
    assertThrows(MavisValidationException.class, () -> MavisCallback.parse(null));
  }

  /** query 字段必须有 {@code =}、不能为空字段，且字段总数不超过 32。 */
  @Test
  void rejectsMalformedOrOversizedQuery() {
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("minimax-cn://auth-callback?broken&accessToken=" + TOKEN));
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("minimax-cn://auth-callback?accessToken=" + TOKEN + "&"));

    StringBuilder atLimit = new StringBuilder("minimax-cn://auth-callback?accessToken=" + TOKEN);
    for (int index = 0; index < 31; index++) {
      atLimit.append("&f").append(index).append("=v");
    }
    assertEquals(TOKEN, MavisCallback.parse(atLimit.toString()).accessToken());

    assertThrows(MavisValidationException.class, () -> MavisCallback.parse(atLimit + "&f31=v"));
  }

  /** 超长回调与超长 token 在长度上限处失败。 */
  @Test
  void rejectsOversizedCallbackAndToken() {
    String oversizedToken = "a".repeat(8_193);
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("minimax-cn://auth-callback?accessToken=" + oversizedToken));
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("minimax-cn://auth-callback?accessToken=" + "a".repeat(16_384)));
  }

  /** 控制字符不允许出现在回调中，删除的 ASCII 空白按 WHATWG 规则先移除。 */
  @Test
  void rejectsControlCharacters() {
    assertThrows(
        MavisValidationException.class,
        () -> MavisCallback.parse("minimax-cn://auth-callback?accessToken=ab\u0000c"));
    assertEquals(
        TOKEN,
        MavisCallback.parse("minimax-cn://auth-callback?accessToken=" + TOKEN + "\n")
            .accessToken());
  }

  /** 解析结果与非法输入错误的 toString 都不能暴露 token。 */
  @Test
  void neverEchoesToken() {
    MavisCallback callback = MavisCallback.parse("minimax-cn://auth-callback?accessToken=" + TOKEN);

    assertFalse(callback.toString().contains(TOKEN));
    assertTrue(callback.toString().contains(MavisRedaction.REDACTED));

    MavisException error =
        assertThrows(
            MavisValidationException.class,
            () ->
                MavisCallback.parse("minimax-cn://auth-callback?accessToken=" + TOKEN + "&broken"));
    assertFalse(error.getMessage().contains(TOKEN));
  }
}
