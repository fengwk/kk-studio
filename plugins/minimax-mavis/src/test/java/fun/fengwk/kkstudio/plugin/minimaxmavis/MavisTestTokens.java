package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 测试用的 JWT 与凭据构造工具：只关心本地可观察的 payload 与 exp，不涉及签名。 */
final class MavisTestTokens {

  /** 固定测试 token 值，任何断言都可以直接引用它来验证去敏是否生效。 */
  static final String TOKEN = "header.payload.signature";

  static final String CLIENT_UUID = "11111111-2222-3333-4444-555555555555";

  /** 固定时钟：2023-11-14T22:13:20Z。 */
  static final long FIXED_EPOCH_MILLIS = 1_700_000_000_000L;

  private MavisTestTokens() {}

  /** 构造 exp 为给定秒数的 token。 */
  static String withExp(long expEpochSecond) {
    return withPayload("{\"exp\":" + expEpochSecond + "}");
  }

  /** 构造 payload 原文由调用方决定的 token，用于覆盖缺失或非数值 exp 的场景。 */
  static String withPayload(String payloadJson) {
    return "header."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
        + ".signature";
  }

  /** 基于固定时钟构造一份未过期凭据。 */
  static MavisCredential credential(MavisRegion region, long expEpochSecond) {
    return new MavisCredential(
        withExp(expEpochSecond), region, Instant.ofEpochSecond(expEpochSecond), CLIENT_UUID);
  }
}
