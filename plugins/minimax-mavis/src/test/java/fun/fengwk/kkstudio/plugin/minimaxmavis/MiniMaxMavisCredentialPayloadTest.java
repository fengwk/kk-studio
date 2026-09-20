package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

/**
 * MiniMax Mavis 凭据载荷（MiniMaxMavisCredentialPayload）严格编解码与去敏测试。
 *
 * <p>验证 JSON 严格编解码：任何未知字段、缺失字段、非数值时间戳、非 JSON 对象均被严格拒绝； 验证 toString() 实现绝不泄漏 access token 明文，以
 * <redacted> 呈现。
 */
class MiniMaxMavisCredentialPayloadTest {

  private static final String SECRET_TOKEN = "super-secret-mavis-token-987654";
  private static final String CLIENT_UUID = "11111111-2222-3333-4444-555555555555";
  private static final Instant OBTAINED_AT = Instant.ofEpochMilli(1_700_000_000_123L);

  /** 验证合法的凭据载荷序列化为规范 JSON，且可被 parse 完全无损还原。 */
  @Test
  void roundTripSerializationPreservesFields() {
    MiniMaxMavisCredentialPayload payload =
        new MiniMaxMavisCredentialPayload(SECRET_TOKEN, CLIENT_UUID, OBTAINED_AT);

    String json = payload.toJson();
    assertTrue(json.contains("\"accessToken\":\"" + SECRET_TOKEN + "\""));
    assertTrue(json.contains("\"clientUuid\":\"" + CLIENT_UUID + "\""));
    assertTrue(json.contains("\"obtainedAt\":" + OBTAINED_AT.toEpochMilli()));

    MiniMaxMavisCredentialPayload parsed = MiniMaxMavisCredentialPayload.parse(json);
    assertEquals(payload.accessToken(), parsed.accessToken());
    assertEquals(payload.clientUuid(), parsed.clientUuid());
    assertEquals(payload.obtainedAt(), parsed.obtainedAt());
    assertEquals(payload, parsed);
  }

  /** 验证未知字段会被严格拒绝（防止格式漂移与未知属性污染）。 */
  @Test
  void parseRejectsUnexpectedFields() {
    String jsonWithExtra =
        "{\"accessToken\":\""
            + SECRET_TOKEN
            + "\",\"clientUuid\":\""
            + CLIENT_UUID
            + "\",\"obtainedAt\":"
            + OBTAINED_AT.toEpochMilli()
            + ",\"extraField\":\"unexpected\"}";

    assertThrows(
        MavisValidationException.class,
        () -> MiniMaxMavisCredentialPayload.parse(jsonWithExtra),
        "Payload with unexpected fields must be rejected");
  }

  /** 验证缺少必需字段会被严格拒绝。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // 缺少 accessToken
        "{\"clientUuid\":\"" + CLIENT_UUID + "\",\"obtainedAt\":" + 1_700_000_000_000L + "}",
        // 缺少 clientUuid
        "{\"accessToken\":\"" + SECRET_TOKEN + "\",\"obtainedAt\":" + 1_700_000_000_000L + "}",
        // 缺少 obtainedAt
        "{\"accessToken\":\"" + SECRET_TOKEN + "\",\"clientUuid\":\"" + CLIENT_UUID + "\"}",
        // 空对象
        "{}"
      })
  void parseRejectsMissingFields(String jsonMissingField) {
    assertThrows(
        MavisValidationException.class,
        () -> MiniMaxMavisCredentialPayload.parse(jsonMissingField),
        () -> "Payload missing required field must be rejected: " + jsonMissingField);
  }

  /** 验证非数值时间戳（字符串、布尔、null）会被严格拒绝。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        // 字符串形式的毫秒
        "{\"accessToken\":\"t\",\"clientUuid\":\"u\",\"obtainedAt\":\"1700000000000\"}",
        // ISO-8601 字符串
        "{\"accessToken\":\"t\",\"clientUuid\":\"u\",\"obtainedAt\":\"2023-11-14T22:13:20Z\"}",
        // 布尔值
        "{\"accessToken\":\"t\",\"clientUuid\":\"u\",\"obtainedAt\":true}",
        // 对象
        "{\"accessToken\":\"t\",\"clientUuid\":\"u\",\"obtainedAt\":{}}"
      })
  void parseRejectsNonIntegralObtainedAt(String invalidObtainedAtJson) {
    assertThrows(
        MavisValidationException.class,
        () -> MiniMaxMavisCredentialPayload.parse(invalidObtainedAtJson),
        () -> "Payload with non-integral obtainedAt must be rejected: " + invalidObtainedAtJson);
  }

  /** 验证非 JSON 对象（数组、标量字符串、畸形 JSON、空字符串）全部拒绝。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "[\"accessToken\", \"clientUuid\"]",
        "\"a simple string\"",
        "12345",
        "true",
        "",
        "   ",
        "not a valid json"
      })
  void parseRejectsNonObjectJson(String nonObjectJson) {
    assertThrows(
        MavisValidationException.class,
        () -> MiniMaxMavisCredentialPayload.parse(nonObjectJson),
        () -> "Non-object JSON must be rejected: " + nonObjectJson);
  }

  /** 验证构造函数自身对空字段的防御性校验。 */
  @Test
  void constructorRejectsBlankOrNullFields() {
    assertThrows(
        MavisValidationException.class,
        () -> new MiniMaxMavisCredentialPayload(null, CLIENT_UUID, OBTAINED_AT));
    assertThrows(
        MavisValidationException.class,
        () -> new MiniMaxMavisCredentialPayload("", CLIENT_UUID, OBTAINED_AT));
    assertThrows(
        MavisValidationException.class,
        () -> new MiniMaxMavisCredentialPayload(SECRET_TOKEN, null, OBTAINED_AT));
    assertThrows(
        MavisValidationException.class,
        () -> new MiniMaxMavisCredentialPayload(SECRET_TOKEN, "  ", OBTAINED_AT));
    assertThrows(
        MavisValidationException.class,
        () -> new MiniMaxMavisCredentialPayload(SECRET_TOKEN, CLIENT_UUID, null));
  }

  /** 验证 toString() 实现严格屏蔽 access token 明文，以 <redacted> 呈现。 */
  @Test
  void toStringRedactsAccessToken() {
    MiniMaxMavisCredentialPayload payload =
        new MiniMaxMavisCredentialPayload(SECRET_TOKEN, CLIENT_UUID, OBTAINED_AT);

    String representation = payload.toString();
    assertFalse(
        representation.contains(SECRET_TOKEN),
        "toString() must never contain the plain access token");
    assertTrue(
        representation.contains(MavisRedaction.REDACTED),
        "toString() must indicate redacted access token");
    assertTrue(representation.contains(CLIENT_UUID), "toString() should contain client UUID");
    assertTrue(
        representation.contains(OBTAINED_AT.toString()), "toString() should contain obtainedAt");
  }
}
