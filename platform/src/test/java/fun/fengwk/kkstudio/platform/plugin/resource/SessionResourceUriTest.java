package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;
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

  /**
   * 自由文本中紧邻规范 UUID 之后的续写一律整体不匹配：路径、查询、片段、更长的 hex/字母 token 都不能被截断成「文本里并不存在的规范
   * URI」，否则未真正被引用的资源会被当作已公开证据。
   */
  @Test
  void scanRejectsContinuationSuffixes() {
    UUID blobId = UUID.randomUUID();
    String canonical = "kkstudio:/resources/" + blobId;
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
            ".txt", // 点续写（句末点号只在分隔符/结束前才算分隔符）
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
      assertEquals(
          List.of(),
          SessionResourceUri.scan("evidence: " + token),
          "scan must not truncate a longer token into " + canonical);
    }
  }

  /** 普通文本分隔符（含句末标点、成对/引用符号、非 ASCII 标点与文本结束）之后的规范 URI 必须照常识别。 */
  @Test
  void scanAcceptsCanonicalUrisFollowedByTextDelimiters() {
    UUID blobId = UUID.randomUUID();
    String canonical = "kkstudio:/resources/" + blobId;
    List<String> delimiters =
        List.of(
            " ", "\n", "\t", ",", ";", ":", "!", ")", "]", "}", ">", "\"", "'", "`", "*", "|", "。",
            "）", "」");

    for (String delimiter : delimiters) {
      assertEquals(
          List.of(blobId),
          SessionResourceUri.scan("see " + canonical + delimiter + "then done"),
          "delimiter " + delimiter + " must end the uri");
    }

    // 文本结束与句末 . / ? 同样是合法边界
    assertEquals(List.of(blobId), SessionResourceUri.scan("see " + canonical));
    assertEquals(List.of(blobId), SessionResourceUri.scan("see " + canonical + "."));
    assertEquals(List.of(blobId), SessionResourceUri.scan("see " + canonical + "?"));
    assertEquals(List.of(blobId), SessionResourceUri.scan("see " + canonical + ". then done"));
    assertEquals(List.of(blobId), SessionResourceUri.scan("see " + canonical + "? then done"));
    assertEquals(List.of(blobId), SessionResourceUri.scan("see (" + canonical + ")"));
    assertEquals(List.of(blobId), SessionResourceUri.scan("see **" + canonical + "**"));
  }

  /** 同一文本中的多个独立规范 URI 必须全部识别、按首次出现顺序返回且去重。 */
  @Test
  void scanReturnsEveryIndependentCanonicalUriInOrder() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    String text =
        "delivered "
            + "kkstudio:/resources/"
            + first
            + ", then "
            + "kkstudio:/resources/"
            + second
            + ".\n"
            + "attachment "
            + "kkstudio:/resources/"
            + third
            + "\nagain "
            + "kkstudio:/resources/"
            + first;

    List<UUID> scanned = SessionResourceUri.scan(text);

    assertEquals(List.of(first, second, third), scanned);
  }

  /** 更长 URL 片段中的相同前缀同样不是平台 URI；无 URI 的文本返回空。 */
  @Test
  void scanIgnoresLongerUrlFragmentsAndTextWithoutUris() {
    UUID blobId = UUID.randomUUID();

    assertEquals(List.of(), SessionResourceUri.scan(null));
    assertEquals(List.of(), SessionResourceUri.scan(""));
    assertEquals(List.of(), SessionResourceUri.scan("no resources here"));
    assertEquals(
        List.of(), SessionResourceUri.scan("https://example.com/kkstudio:/resources/" + blobId));
    assertEquals(List.of(), SessionResourceUri.scan("xkkstudio:/resources/" + blobId));
  }
}
