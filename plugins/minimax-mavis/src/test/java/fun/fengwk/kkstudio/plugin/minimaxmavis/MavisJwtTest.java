package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** JWT 数值 exp 的宽松解析与凭据本地时限规则。 */
class MavisJwtTest {

  /** 只有「三段 + base64url payload + 对象 + 数值 exp」才产生本地时限。 */
  @Test
  void readsNumericExpOnly() {
    assertEquals(
        Instant.ofEpochSecond(1_700_000_000L),
        MavisJwt.numericExpiresAt(MavisTestTokens.withExp(1_700_000_000L)).orElseThrow());

    assertTrue(MavisJwt.numericExpiresAt("not-a-jwt").isEmpty());
    assertTrue(MavisJwt.numericExpiresAt("a.b").isEmpty());
    assertTrue(MavisJwt.numericExpiresAt("a.!!!.c").isEmpty());
    assertTrue(MavisJwt.numericExpiresAt(MavisTestTokens.withPayload("{}")).isEmpty());
    assertTrue(
        MavisJwt.numericExpiresAt(MavisTestTokens.withPayload("{\"exp\":\"123\"}")).isEmpty());
    assertTrue(MavisJwt.numericExpiresAt(MavisTestTokens.withPayload("{\"exp\":true}")).isEmpty());
    assertTrue(MavisJwt.numericExpiresAt(MavisTestTokens.withPayload("[1,2]")).isEmpty());
    // 超出 Instant 范围的 exp 与无法 base64url 解出的 payload 都退化为「没有本地时限」。
    assertTrue(MavisJwt.numericExpiresAt(MavisTestTokens.withPayload("{\"exp\":1e18}")).isEmpty());
    assertTrue(MavisJwt.numericExpiresAt("h.a.s").isEmpty());
    assertTrue(
        MavisJwt.numericExpiresAt(MavisTestTokens.withPayload("{\"exp\":\"soon\"}")).isEmpty());
    assertTrue(MavisJwt.numericExpiresAt(null).isEmpty());
    assertTrue(MavisJwt.numericExpiresAt("  ").isEmpty());
  }

  /** 凭据只在 JWT 具有数值 exp 且剩余时间严格超过安全余量时才可构造。 */
  @Test
  void credentialRequiresFreshNumericExpiry() {
    Instant now = Instant.ofEpochSecond(1_700_000_000L);

    MavisCredential credential =
        MavisCredential.fromJwt(
            MavisTestTokens.withExp(1_700_003_600L),
            MavisRegion.CN,
            now,
            MavisTestTokens.CLIENT_UUID);
    assertEquals(Instant.ofEpochSecond(1_700_003_600L), credential.expiresAt());
    assertEquals(MavisRegion.CN, credential.region());

    assertThrows(
        MavisAuthException.class,
        () ->
            MavisCredential.fromJwt(
                MavisTestTokens.withPayload("{}"),
                MavisRegion.CN,
                now,
                MavisTestTokens.CLIENT_UUID));
    assertThrows(
        MavisAuthException.class,
        () ->
            MavisCredential.fromJwt(
                MavisTestTokens.withExp(1_699_999_000L),
                MavisRegion.CN,
                now,
                MavisTestTokens.CLIENT_UUID));
    // 恰好等于安全余量的 token 已不再可用，严格大于才可以。
    assertThrows(
        MavisAuthException.class,
        () ->
            MavisCredential.fromJwt(
                MavisTestTokens.withExp(1_700_000_030L),
                MavisRegion.CN,
                now,
                MavisTestTokens.CLIENT_UUID));
    assertThrows(
        MavisAuthException.class,
        () -> MavisCredential.fromJwt("", MavisRegion.CN, now, MavisTestTokens.CLIENT_UUID));
  }

  /** 凭据的 toString 与字段错误都不暴露 token。 */
  @Test
  void credentialNeverPrintsToken() {
    MavisCredential credential = MavisTestTokens.credential(MavisRegion.EN, 1_800_000_000L);

    assertFalse(credential.toString().contains(credential.token()));
    assertTrue(credential.toString().contains(MavisRedaction.REDACTED));
    assertThrows(
        MavisValidationException.class,
        () -> new MavisCredential(" ", MavisRegion.CN, Instant.EPOCH, MavisTestTokens.CLIENT_UUID));
    assertThrows(
        MavisValidationException.class,
        () -> new MavisCredential("token", MavisRegion.CN, Instant.EPOCH, " "));
  }
}
