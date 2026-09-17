package fun.fengwk.kkstudio.harness.common.resource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** ResourceRef 全 scheme / 全限制 / 规范 / 安全边界的构造校验测试。 */
class ResourceRefTest {

  private static final String SHA_HELLO =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
  private static final String SHA_EMPTY =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  /** 验证规范 data URI 语法，并校验载荷长度与 SHA-256 摘要一致性。 */
  @Test
  void acceptsCanonicalDataUrisAndVerifiesSizeAndSha() {
    assertDoesNotThrow(
        () -> new ResourceRef("data:text/plain,hello", "text/plain", null, 5L, SHA_HELLO));
    assertDoesNotThrow(
        () ->
            new ResourceRef("data:text/plain;base64,aGVsbG8=", "text/plain", null, 5L, SHA_HELLO));
    // 空载荷：size 0 且 sha 为空串摘要。
    assertDoesNotThrow(
        () -> new ResourceRef("data:text/plain,", "text/plain", null, 0L, SHA_EMPTY));
    // 百分号编码的非 unreserved 字节（空格）合法。
    assertDoesNotThrow(
        () -> new ResourceRef("data:text/plain,a%20b", "text/plain", null, 3L, sha256("a b")));
  }

  /** 非 base64 data 载荷的 frozen 表示：原始 ASCII 仅限 unreserved 与 '/'，其余字节必须大写 %XX。 */
  @Test
  void dataUriPayloadRequiresCanonicalPercentEncoding() {
    // 逗号/分号/冒号/问号/井号：原始形式拒绝，编码形式接受。
    for (String[] pair :
        List.of(
            new String[] {",", "%2C"},
            new String[] {";", "%3B"},
            new String[] {":", "%3A"},
            new String[] {"?", "%3F"},
            new String[] {"#", "%23"})) {
      byte[] decoded = ("a" + pair[0] + "b").getBytes(StandardCharsets.UTF_8);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ResourceRef(
                  "data:text/plain,a" + pair[0] + "b", "text/plain", null, 3L, sha256(decoded)));
      assertDoesNotThrow(
          () ->
              new ResourceRef(
                  "data:text/plain,a" + pair[1] + "b", "text/plain", null, 3L, sha256(decoded)));
    }
    // 斜杠：原始接受、编码拒绝。
    assertDoesNotThrow(
        () ->
            new ResourceRef(
                "data:text/plain,a/b",
                "text/plain",
                null,
                3L,
                sha256("a/b".getBytes(StandardCharsets.UTF_8))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceRef(
                "data:text/plain,a%2Fb",
                "text/plain", null, 3L, sha256("a/b".getBytes(StandardCharsets.UTF_8))));
  }

  /** 验证合法 file, s3, http/https URI 接受，且 digest 按 scheme 约束可选或必选。 */
  @Test
  void acceptsFileS3HttpUrisAndOptionalDigest() {
    assertDoesNotThrow(
        () -> new ResourceRef("file:///a/b.txt", "text/plain", null, 3L, sha256("abc")));
    assertDoesNotThrow(
        () ->
            new ResourceRef(
                "s3://my-bucket.example.com/dir/file.txt",
                "application/octet-stream",
                "file.txt",
                3L,
                sha256("abc")));
    // http/https 不要求 size/sha。
    ResourceRef http = new ResourceRef("https://example.com/a/b", "text/plain", null, null, null);
    assertNull(http.size());
    assertNull(http.sha256());
    assertDoesNotThrow(
        () -> new ResourceRef("http://localhost:8080/x", "text/plain", null, 1L, sha256("x")));
    assertDoesNotThrow(
        () -> new ResourceRef("https://example.com", "text/plain", "root", null, null));
  }

  /** 验证超长 URI 在边界处被拒绝。 */
  @Test
  void rejectsOversizedUri() {
    String atLimit = "http://example.com/" + "a".repeat(ResourceRef.MAX_URI_UTF8_BYTES - 19);
    assertDoesNotThrow(() -> new ResourceRef(atLimit, "text/plain", null, null, null));
    String overLimit = atLimit + "a";
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef(overLimit, "text/plain", null, null, null));
  }

  /** 验证拒绝控制字符、NUL 与反斜杠。 */
  @Test
  void rejectsControlCharsNulAndBackslash() {
    for (String uri :
        List.of(
            "http://example.com/a\u0000b",
            "file:///a\tb",
            "https://x/a\nb",
            "s3://b/a\\b",
            "http://example.com/a\u007fb")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ResourceRef(uri, "text/plain", null, null, null));
    }
  }

  /** 验证非规范 ASCII 与非法百分号转义在构造期被拒绝。 */
  @Test
  void rejectsNonCanonicalAsciiAndPercentEscapes() {
    for (String uri :
        List.of(
            "http://example.com/a b", // 裸空格：toASCIIString 会编码，非规范输入。
            "http://example.com/\u00e9", // 非 ASCII：toASCIIString 会编码。
            "http://example.com/a%2f", // 小写 hex。
            "http://example.com/a%61", // 编码 unreserved 'a'。
            "http://example.com/a%41", // 编码 unreserved 'A'。
            "http://example.com/a%2e", // 编码 unreserved '.'。
            "http://example.com/a%2D", // 编码 unreserved '-'。
            "http://example.com/a%5F", // 编码 unreserved '_'。
            "http://example.com/a%7E", // 编码 unreserved '~'。
            "http://example.com/a%2F", // 编码 slash。
            "http://example.com/a%5C", // 编码 backslash。
            "http://example.com/a%00", // 编码 NUL。
            "http://example.com/a%2", // 截断的转义。
            "http://example.com/a%2g", // 低 hex 位非法。
            "http://example.com/a%G1")) { // 非 hex。
      assertThrows(
          IllegalArgumentException.class,
          () -> new ResourceRef(uri, "text/plain", null, null, null));
    }
  }

  /** 验证拒绝不支持的 scheme、大写 scheme 与相对 URI。 */
  @Test
  void rejectsUnsupportedOrUppercaseSchemesAndRelativeUris() {
    for (String uri :
        List.of(
            "ftp://example.com/x", "HTTP://example.com/x", "Data:text/plain,x", "relative/path")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ResourceRef(uri, "text/plain", null, null, null));
    }
  }

  /** 验证 mediaType 格式与尺寸约束。 */
  @Test
  void rejectsInvalidMediaTypes() {
    for (String mediaType :
        List.of(
            "text", // 缺 subtype。
            "text/", // 空 subtype。
            "Text/Plain", // 大写。
            "text/plain;charset=utf-8", // 参数。
            "text/ plain", // 空白。
            "text/pla\u0001in", // 控制字符。
            "text/pla/in", // 非法 token 字符。
            "a".repeat(254) + "/b", // 256 ASCII 字节，超上限。
            "")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ResourceRef("https://example.com/x", mediaType, null, null, null));
    }
    // 恰好在 255 ASCII 字节内合法。
    assertDoesNotThrow(
        () -> new ResourceRef("https://example.com/x", "a".repeat(253) + "/b", null, null, null));
  }

  /** 验证可选资源名、尺寸与 sha256 格式校验。 */
  @Test
  void validatesNameSizeAndSha() {
    ResourceRef valid =
        new ResourceRef("https://example.com/x", "text/plain", "报告.txt", 3L, sha256("abc"));
    assertEquals("报告.txt", valid.name());

    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("https://example.com/x", "text/plain", "  ", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("https://example.com/x", "text/plain", "a\u0001b", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("https://example.com/x", "text/plain", "a\uD800b", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("https://example.com/x", "text/plain", "a".repeat(513), null, null));
    assertDoesNotThrow(
        () -> new ResourceRef("https://example.com/x", "text/plain", "a".repeat(512), null, null));

    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("https://example.com/x", "text/plain", null, -1L, null));
    for (String sha :
        List.of("ABC".repeat(21) + "A", "abc", "z".repeat(64), "a".repeat(63), "a".repeat(65))) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new ResourceRef("https://example.com/x", "text/plain", null, 1L, sha));
    }
  }

  /** 有界 UTF-8 计数：1/2/3/4 字节编码精确计数，超限提前停止，代理项严格校验。 */
  @Test
  void utf8LengthUpToCountsExactBytesAndStopsEarly() {
    // 精确计数：ASCII(1) + é(2) + 中(3) + 𝄞(4) = 10 字节。
    assertEquals(10, ResourceRef.utf8LengthUpTo("aé中𝄞", "v", Integer.MAX_VALUE));
    assertEquals(2, ResourceRef.utf8LengthUpTo("é", "v", 2));
    assertEquals(3, ResourceRef.utf8LengthUpTo("中", "v", 3));
    assertEquals(4, ResourceRef.utf8LengthUpTo("𝄞", "v", 4));
    assertEquals(1, ResourceRef.utf8LengthUpTo("a", "v", 1));

    // 超限提前停止：返回计数 > max，且不扫描剩余输入。
    assertTrue(ResourceRef.utf8LengthUpTo("é", "v", 1) > 1);
    assertTrue(ResourceRef.utf8LengthUpTo("中", "v", 2) > 2);
    assertTrue(ResourceRef.utf8LengthUpTo("𝄞", "v", 3) > 3);

    // 未配对代理项拒绝（与 utf8Length 的严格语义一致）。
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceRef.utf8LengthUpTo("\uD800x", "v", Integer.MAX_VALUE));
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceRef.utf8LengthUpTo("x\uDC00", "v", Integer.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, () -> ResourceRef.utf8LengthUpTo("x", "v", -1));
  }

  /** 验证 file URI 规范与段约束。 */
  @Test
  void rejectsFileUriViolations() {
    for (String uri :
        List.of(
            "file:/a", // 无 authority，必须 file:///。
            "file://localhost/a", // 非空 authority。
            "file:///a?query=1", // query。
            "file:///a#frag", // fragment。
            "file:////a", // 双前导斜杠。
            "file:///", // 空路径。
            "file:///a/../b", // dot segment。
            "file:///a/./b", // dot segment。
            "file:///a//b", // 空 segment。
            "file:///a/", // 空 segment。
            "file:///a%20b", // file path 禁止 percent encoding，adapter 不得二次解释。
            "file:///a%FFb", // 同时拒绝非法 UTF-8 字节序列。
            "file:///a b", // 非保守 ASCII。
            "file:///a|b")) { // 非法 URI 字符。
      assertThrows(
          IllegalArgumentException.class,
          () -> new ResourceRef(uri, "text/plain", null, 3L, sha256("abc")));
    }
    // file/s3 必须携带 size+sha；http 不需要。
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("file:///a", "text/plain", null, null, sha256("abc")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("file:///a", "text/plain", null, 3L, null));
  }

  /** 验证 s3 URI 的 bucket 与 key 命名规则。 */
  @Test
  void rejectsS3UriViolations() {
    for (String uri :
        List.of(
            "s3://Bucket/key", // 大写 bucket。
            "s3:///key", // 空 bucket。
            "s3://ab/key", // bucket 过短。
            "s3://.bucket/key", // 点开头。
            "s3://bucket./key", // 点结尾。
            "s3://a..b/key", // 连续点。
            "s3://a-.b/key", // label 以 '-' 结尾。
            "s3://a.-b/key", // label 以 '-' 开头。
            "s3://192.168.5.4/key", // 形如 IP。
            "s3://u@bucket/key", // userinfo。
            "s3://bucket:9000/key", // port。
            "s3://bucket/key?x=1", // query。
            "s3://bucket/key#f", // fragment。
            "s3://bucket", // 无 key path。
            "s3://bucket/", // 空 key。
            "s3://bucket/.", // dot segment。
            "s3://bucket/..", // dot segment。
            "s3://bucket/a/../b", // dot segment。
            "s3://bucket/a/./b", // dot segment。
            "s3://bucket/a//b", // 空 segment。
            "s3://bucket/a%20b", // 百分号编码。
            "s3://bucket/a b", // 非保守 ASCII。
            "s3://b%C3%BCcket/key")) { // 编码 host。
      assertThrows(
          IllegalArgumentException.class,
          () -> new ResourceRef(uri, "text/plain", null, 3L, sha256("abc")));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("s3://bucket/key", "text/plain", null, null, sha256("abc")));
  }

  /** 验证 http/https URI 端口与 host 约束。 */
  @Test
  void rejectsHttpUriViolations() {
    for (String uri :
        List.of(
            "http://EXAMPLE.com/", // 大写 host。
            "http:///x", // 空 host。
            "http://%65xample.com/", // 编码 host：getHost 为 null。
            "http://user@example.com/", // userinfo。
            "http://example.com/?a=b", // query（presign 场景）。
            "https://example.com/a#f", // fragment。
            "http://example.com/a/../b", // dot segment。
            "http://example.com/a/./b", // dot segment。
            "https://example.com:/x", // 空端口不是 canonical authority。
            "https://example.com:08443/x", // 端口禁止前导零。
            "http://example.com:0/", // 端口必须为 1-65535。
            "http://example.com:65536/", // 端口越界。
            "http://example.com:80/", // http 显式默认端口。
            "https://example.com:443/")) { // https 显式默认端口。
      assertThrows(
          IllegalArgumentException.class,
          () -> new ResourceRef(uri, "text/plain", null, null, null));
    }
    // 非默认显式端口合法（http 8080 / https 8443）。
    assertDoesNotThrow(
        () -> new ResourceRef("http://example.com:8080/x", "text/plain", null, null, null));
    assertDoesNotThrow(
        () -> new ResourceRef("https://example.com:8443/x", "text/plain", null, null, null));
    assertDoesNotThrow(
        () -> new ResourceRef("https://[2001:db8::1]/x", "text/plain", null, null, null));
  }

  /** 验证格式错误的 data URI 被拒绝。 */
  @Test
  void rejectsMalformedDataUris() {
    for (String uri :
        List.of(
            "data:application/json,{}", // header 与 mediaType 不匹配。
            "data:text/plain;charset=utf-8,x", // 带参数 header。
            "data:text/plain", // 缺逗号。
            "data:text/plain;base64,aGVsbG8", // 非规范 padding。
            "data:text/plain;base64,aGVs bG8=", // base64 空白。
            "data:text/plain;base64,!!!", // 非法 base64 字符。
            "data:text/plain;base64,aGVsbG8===", // 错误 padding。
            "data:text/plain;base64,aGVsbG9=", // 非零 pad bits，与 hello 解码相同但不 canonical。
            "data:text/plain,a%6c", // 小写 hex。
            "data:text/plain,a%61", // 编码 unreserved。
            "data:text/plain,a%2Fb", // 编码 slash。
            "data:text/plain,a?b", // 裸 '?'。
            "data:text/plain,a#b")) { // fragment。
      assertThrows(
          IllegalArgumentException.class,
          () -> new ResourceRef(uri, "text/plain", null, 5L, SHA_HELLO));
    }
    // size/sha 必须与解码字节一致。
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("data:text/plain,hello", "text/plain", null, 6L, SHA_HELLO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("data:text/plain,hello", "text/plain", null, 5L, SHA_EMPTY));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef("data:text/plain,hello", "text/plain", null, null, SHA_HELLO));
  }

  /** 验证超限载荷在 URI 边界或解码边界被拒绝。 */
  @Test
  void rejectsOversizedDecodedDataBeforeOrAtUriLimit() {
    // 解码 65537 字节：raw 长度未超 URI 上限，但解码后超限。
    String overDecoded = "data:text/plain," + "a".repeat(ResourceRef.MAX_DATA_DECODED_BYTES + 1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceRef(
                overDecoded,
                "text/plain",
                null,
                (long) ResourceRef.MAX_DATA_DECODED_BYTES + 1,
                sha256("a".repeat(ResourceRef.MAX_DATA_DECODED_BYTES + 1))));
    // 恰好 65536 字节解码合法。
    String atLimit = "data:text/plain," + "a".repeat(ResourceRef.MAX_DATA_DECODED_BYTES);
    assertDoesNotThrow(
        () ->
            new ResourceRef(
                atLimit,
                "text/plain",
                null,
                (long) ResourceRef.MAX_DATA_DECODED_BYTES,
                sha256("a".repeat(ResourceRef.MAX_DATA_DECODED_BYTES))));
    // 百分号编码把小 payload 撑到 URI 上限之外：URI 限制先于解码生效。
    String percentEncodedOversized =
        "data:text/plain," + "%FF".repeat((ResourceRef.MAX_URI_UTF8_BYTES / 3) + 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef(percentEncodedOversized, "text/plain", null, 1L, sha256("x")));
  }

  /** blob-upload 是 Daemon 直传对象存储后的瞬态引用：必须精确 {@code blob-upload:<canonical-uuid>} 且携带非空 size/sha。 */
  @Test
  void acceptsCanonicalBlobUploadUrisAndRoundTripsUploadId() {
    UUID uploadId = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    ResourceRef ref =
        new ResourceRef(
            ResourceRef.blobUploadUri(uploadId), "text/plain", "a.txt", 3L, sha256("abc"));
    assertEquals(uploadId, ref.blobUploadId());
    assertEquals(uploadId, ResourceRef.blobUploadId("blob-upload:" + uploadId));
    // 非 blob-upload scheme 一律不识别为上传引用。
    assertNull(ResourceRef.blobUploadId("file:///export/abc"));
    assertNull(ResourceRef.blobUploadId(null));
  }

  /** blob-upload 只接受规范小写 UUID：大写、非 UUID、缺少 scheme 或携带额外成分都返回 null。 */
  @Test
  void blobUploadIdRejectsNonCanonicalForms() {
    UUID uploadId = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    assertNull(ResourceRef.blobUploadId("blob-upload:" + uploadId.toString().toUpperCase()));
    assertNull(ResourceRef.blobUploadId("blob-upload:not-a-uuid"));
    assertNull(ResourceRef.blobUploadId(uploadId.toString()));
    assertNull(ResourceRef.blobUploadId("blob-upload:"));
    assertNull(ResourceRef.blobUploadId("blob-upload:" + uploadId + "/x"));
    assertNull(ResourceRef.blobUploadId("blob-upload:" + uploadId + "?a=b"));
    assertNull(ResourceRef.blobUploadId("x-blob-upload:" + uploadId));
  }

  /** blob-upload 必须携带非空 size/sha，且不得携带 authority/query/fragment。 */
  @Test
  void rejectsBlobUploadUriViolations() {
    UUID uploadId = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    String uri = ResourceRef.blobUploadUri(uploadId);
    assertThrows(
        IllegalArgumentException.class, () -> new ResourceRef(uri, "text/plain", null, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> new ResourceRef(uri, "text/plain", null, 1L, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef(uri + "?a=b", "text/plain", null, 1L, sha256("a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef(uri.toUpperCase(), "text/plain", null, 1L, sha256("a")));
  }

  /** 验证 null URI 被拒绝。 */
  @Test
  void rejectsNullUri() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceRef(null, "text/plain", null, null, null));
  }

  private static String sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
