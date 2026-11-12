package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 会话 Resource URI 规范解析单元测试。 */
class SessionResourceUriTest {

  /** 规范 URI 只由 blob id 派生，并与严格解析互为往返；缺少 blob id 时拒绝而不是产生半成品 URI。 */
  @Test
  void formatsCanonicalUriFromBlobId() {
    UUID blobId = UUID.fromString("3f1a2b4c-5d6e-4f70-8192-a3b4c5d6e7f8");

    String uri = SessionResourceUri.format(blobId);

    assertEquals("resource://files/3f1a2b4c-5d6e-4f70-8192-a3b4c5d6e7f8", uri);
    assertEquals(Optional.of(blobId), SessionResourceUri.parse(uri));
    assertThrows(NullPointerException.class, () -> SessionResourceUri.format(null));
  }

  /** 合法规范的小写 UUID 必须能正确解析出 blob id。 */
  @Test
  void parsesCanonicalFormSuccessfully() {
    UUID expectedBlobId = UUID.randomUUID();
    String uri = "resource://files/" + expectedBlobId;

    Optional<UUID> actual = SessionResourceUri.parse(uri);

    assertTrue(actual.isPresent());
    assertEquals(expectedBlobId, actual.get());
  }

  /** 包含大写十六进制字符的 UUID 必须确定性拒绝，防大小写绕过。 */
  @Test
  void rejectsUppercaseUuid() {
    String uppercaseUuid = "A87D0E54-6A92-4C61-B9D1-331B2D98822A";
    String uri = "resource://files/" + uppercaseUuid;

    Optional<UUID> actual = SessionResourceUri.parse(uri);

    assertTrue(actual.isEmpty());
  }

  /** 路径中包含多余段落（如 sub-path）必须确定性拒绝。 */
  @Test
  void rejectsExtraPathSegments() {
    UUID blobId = UUID.randomUUID();
    String uri = "resource://files/" + blobId + "/extra";

    Optional<UUID> actual = SessionResourceUri.parse(uri);

    assertTrue(actual.isEmpty());
  }

  /** 非规范前缀（如 other path 或缺少斜杠）必须确定性拒绝。 */
  @Test
  void rejectsNonCanonicalPrefix() {
    UUID blobId = UUID.randomUUID();

    assertTrue(SessionResourceUri.parse("resource://other/" + blobId).isEmpty());
    assertTrue(SessionResourceUri.parse("resource://files" + blobId).isEmpty());
    assertTrue(SessionResourceUri.parse("resource:/files/" + blobId).isEmpty());
  }

  /** 其他 scheme（如 http/https）必须确定性拒绝。 */
  @Test
  void rejectsOtherSchemes() {
    UUID blobId = UUID.randomUUID();

    assertTrue(SessionResourceUri.parse("https://example.com/resources/" + blobId).isEmpty());
    assertTrue(SessionResourceUri.parse("http://example.com/resources/" + blobId).isEmpty());
    assertTrue(SessionResourceUri.parse("kks:/resources/" + blobId).isEmpty());
  }

  /** 携带查询参数或锚点片段的 URI 必须确定性拒绝。 */
  @Test
  void rejectsQueryAndFragment() {
    UUID blobId = UUID.randomUUID();

    assertTrue(SessionResourceUri.parse("resource://files/" + blobId + "?download=1").isEmpty());
    assertTrue(SessionResourceUri.parse("resource://files/" + blobId + "#section").isEmpty());
  }

  /** 空 UUID、空串或 null 必须确定性拒绝。 */
  @Test
  void rejectsNullEmptyAndPrefixOnly() {
    assertTrue(SessionResourceUri.parse(null).isEmpty());
    assertTrue(SessionResourceUri.parse("").isEmpty());
    assertTrue(SessionResourceUri.parse("   ").isEmpty());
    assertTrue(SessionResourceUri.parse("resource://files/").isEmpty());
  }

  /** 格式错误（如缺少连字符或包含非法字符）的 UUID 必须确定性拒绝。 */
  @Test
  void rejectsMalformedUuidCharacters() {
    assertTrue(SessionResourceUri.parse("resource://files/not-a-valid-uuid-format-here").isEmpty());
    assertTrue(
        SessionResourceUri.parse("resource://files/00000000000000000000000000000000").isEmpty());
    assertTrue(
        SessionResourceUri.parse("resource://files/00000000-0000-0000-0000-00000000000g")
            .isEmpty());
  }

  /** 规范 UUID 之后的续写一律整体不匹配：路径、查询、片段、更长的 hex/字母 token 都不能被截断成「规范 URI」。 */
  @Test
  void parseRejectsContinuationSuffixes() {
    UUID blobId = UUID.randomUUID();
    String canonical = "resource://files/" + blobId;
    List<String> suffixes =
        List.of(
            "/suffix", // 路径续写
            "/", // 目录续写
            "?token", // 查询参数
            "?download=1",
            "#anchor", // 片段
            "#",
            "0f", // 更长的 hex token
            "abc", // 更长的字母 token
            "Z",
            "_draft", // 标识符续写
            "-report", // 连字符续写
            ".txt", // 点续写
            "..",
            "?.",
            "%20", // 百分号编码续写
            "&x=1", // 查询分隔续写
            "=1",
            "+1",
            "~1",
            "name@example.com");

    for (String suffix : suffixes) {
      String token = canonical + suffix;
      assertTrue(SessionResourceUri.parse(token).isEmpty(), "parse must reject " + token);
    }
  }
}
