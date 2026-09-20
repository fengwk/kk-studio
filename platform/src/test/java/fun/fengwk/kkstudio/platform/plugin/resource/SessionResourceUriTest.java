package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

/** 会话 Resource URI 规范解析单元测试。 */
class SessionResourceUriTest {

  /** 合法规范的小写 UUID 必须能正确解析出 blob id。 */
  @Test
  void parsesCanonicalFormSuccessfully() {
    UUID expectedBlobId = UUID.randomUUID();
    String uri = "kkstudio:/resources/" + expectedBlobId;

    Optional<UUID> actual = SessionResourceUri.parse(uri);

    assertTrue(actual.isPresent());
    assertEquals(expectedBlobId, actual.get());
  }

  /** 包含大写十六进制字符的 UUID 必须确定性拒绝，防大小写绕过。 */
  @Test
  void rejectsUppercaseUuid() {
    String uppercaseUuid = "A87D0E54-6A92-4C61-B9D1-331B2D98822A";
    String uri = "kkstudio:/resources/" + uppercaseUuid;

    Optional<UUID> actual = SessionResourceUri.parse(uri);

    assertTrue(actual.isEmpty());
  }

  /** 路径中包含多余段落（如 sub-path）必须确定性拒绝。 */
  @Test
  void rejectsExtraPathSegments() {
    UUID blobId = UUID.randomUUID();
    String uri = "kkstudio:/resources/" + blobId + "/extra";

    Optional<UUID> actual = SessionResourceUri.parse(uri);

    assertTrue(actual.isEmpty());
  }

  /** 非规范前缀（如 other path 或缺少斜杠）必须确定性拒绝。 */
  @Test
  void rejectsNonCanonicalPrefix() {
    UUID blobId = UUID.randomUUID();

    assertTrue(SessionResourceUri.parse("kkstudio:/other/" + blobId).isEmpty());
    assertTrue(SessionResourceUri.parse("kkstudio:/resources" + blobId).isEmpty());
    assertTrue(SessionResourceUri.parse("kkstudio:resources/" + blobId).isEmpty());
  }

  /** 其他 scheme（如 http/https）必须确定性拒绝。 */
  @Test
  void rejectsOtherSchemes() {
    UUID blobId = UUID.randomUUID();

    assertTrue(SessionResourceUri.parse("https://example.com/resources/" + blobId).isEmpty());
    assertTrue(SessionResourceUri.parse("http://example.com/resources/" + blobId).isEmpty());
  }

  /** 携带查询参数或锚点片段的 URI 必须确定性拒绝。 */
  @Test
  void rejectsQueryAndFragment() {
    UUID blobId = UUID.randomUUID();

    assertTrue(SessionResourceUri.parse("kkstudio:/resources/" + blobId + "?download=1").isEmpty());
    assertTrue(SessionResourceUri.parse("kkstudio:/resources/" + blobId + "#section").isEmpty());
  }

  /** 空 UUID、空串或 null 必须确定性拒绝。 */
  @Test
  void rejectsNullEmptyAndPrefixOnly() {
    assertTrue(SessionResourceUri.parse(null).isEmpty());
    assertTrue(SessionResourceUri.parse("").isEmpty());
    assertTrue(SessionResourceUri.parse("   ").isEmpty());
    assertTrue(SessionResourceUri.parse("kkstudio:/resources/").isEmpty());
  }

  /** 格式错误（如缺少连字符或包含非法字符）的 UUID 必须确定性拒绝。 */
  @Test
  void rejectsMalformedUuidCharacters() {
    assertTrue(
        SessionResourceUri.parse("kkstudio:/resources/not-a-valid-uuid-format-here").isEmpty());
    assertTrue(
        SessionResourceUri.parse("kkstudio:/resources/00000000000000000000000000000000").isEmpty());
    assertTrue(
        SessionResourceUri.parse("kkstudio:/resources/00000000-0000-0000-0000-00000000000g")
            .isEmpty());
  }
}
