package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** LIST_DIRECTORY 请求/响应 payload 的编解码与严格拒绝契约。 */
class DaemonDirectoryCodecTest {

  private static final String REQUEST_ID = "3f0c6b2e-8d1a-4f5e-9c2b-1a2b3c4d5e6f";

  private final DaemonDirectoryCodec codec = new DaemonDirectoryCodec();

  @Test
  void roundTripsListDirectoryRequestIncludingRoot() {
    assertEquals(
        new DaemonDirectoryCodec.ListDirectoryRequest(REQUEST_ID, "src/main"),
        codec.decodeRequest(
            codec.encodeRequest(
                new DaemonDirectoryCodec.ListDirectoryRequest(REQUEST_ID, "src/main"))));
    assertEquals(
        new DaemonDirectoryCodec.ListDirectoryRequest(REQUEST_ID, "."),
        codec.decodeRequest(
            codec.encodeRequest(new DaemonDirectoryCodec.ListDirectoryRequest(REQUEST_ID, "."))));
  }

  @Test
  void roundTripsDirectoryListedIncludingOptionalGitBranch() {
    DaemonDirectoryCodec.DirectoryListed listed =
        new DaemonDirectoryCodec.DirectoryListed(
            REQUEST_ID,
            "src",
            "src",
            ".",
            true,
            "main",
            List.of(
                new DaemonDirectoryCodec.DirectoryEntry("main", "src/main"),
                new DaemonDirectoryCodec.DirectoryEntry("test", "src/test")));
    DaemonDirectoryCodec.DirectoryListed decoded = codec.decodeListed(codec.encodeListed(listed));
    assertEquals(listed, decoded);
    assertTrue(decoded.truncated());
    assertEquals("main", decoded.gitBranch());

    DaemonDirectoryCodec.DirectoryListed withoutBranch =
        new DaemonDirectoryCodec.DirectoryListed(REQUEST_ID, ".", ".", ".", false, null, List.of());
    DaemonDirectoryCodec.DirectoryListed decodedWithout =
        codec.decodeListed(codec.encodeListed(withoutBranch));
    assertEquals(withoutBranch, decodedWithout);
    assertNull(decodedWithout.gitBranch());
  }

  @Test
  void roundTripsDirectoryListFailed() {
    DaemonDirectoryCodec.DirectoryListFailed failed =
        new DaemonDirectoryCodec.DirectoryListFailed(
            REQUEST_ID, "missing", DaemonDirectoryFailureCode.NOT_FOUND, "path does not exist");
    assertEquals(failed, codec.decodeFailed(codec.encodeFailed(failed)));
  }

  @Test
  void rejectsInvalidWirePaths() {
    assertInvalidPath(null);
    assertInvalidPath("");
    assertInvalidPath(" ");
    assertInvalidPath("/");
    assertInvalidPath("/abs");
    assertInvalidPath("a//b");
    assertInvalidPath("a/./b");
    assertInvalidPath("a/../b");
    assertInvalidPath("..");
    assertInvalidPath("a/");
    assertInvalidPath("/a/b");
    assertInvalidPath("a\u0000b");
    assertInvalidPath("a\nb");
    // wire 段一律使用 '/'：反斜杠在 Unix/Windows 上都必须拒绝，不能依赖本地 Path 解析。
    assertInvalidPath("a\\b");
    assertInvalidPath("a\\b/c");
    assertInvalidPath("\\");
    assertInvalidPath("a/b\\c");
    // Windows drive absolute / drive-relative 前缀跨平台拒绝（即使宿主机不是 Windows）。
    assertInvalidPath("C:/x");
    assertInvalidPath("C:x");
    assertInvalidPath("c:/x");
    assertInvalidPath("Z:");
    assertInvalidPath("C:/x/y");
    assertInvalidPath("C:x/y");
  }

  /** requestId 必须是 canonical UUID：缺失/空白/非 UUID/非 canonical 文本都拒绝。 */
  @Test
  void rejectsMissingOrNonCanonicalRequestIds() {
    assertThrows(DaemonProtocolException.class, () -> codec.decodeRequest("{\"path\":\"src\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeRequest("{\"requestId\":\"\",\"path\":\"src\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeRequest("{\"requestId\":\"not-a-uuid\",\"path\":\"src\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeRequest(
                "{\"requestId\":\"ABCDEF00-0000-0000-0000-000000000000\",\"path\":\"src\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDirectoryCodec.ListDirectoryRequest("not-a-uuid", "src"));
    // 合法 canonical UUID（大写输入的 UUID.fromString 解析失败/非 canonical 都拒绝，见上）。
    new DaemonDirectoryCodec.ListDirectoryRequest(UUID.randomUUID().toString(), "src");
  }

  @Test
  void rejectsUnknownFieldsAndMalformedListedPayloads() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeRequest(
                "{\"requestId\":\"" + REQUEST_ID + "\",\"path\":\".\",\"extra\":1}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeRequest("{\"requestId\":\"" + REQUEST_ID + "\"}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeRequest("{\"extra\":1}"));
    // entry 只允许 {name,path}：extra 字段与旧 displayPath 字段都拒绝。
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[{\"name\":\"a\",\"path\":\"a\",\"extra\":1}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[{\"name\":\"a\",\"path\":\"a\",\"displayPath\":\"a\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[{\"name\":\"a\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":\"no\","
                    + "\"entries\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"entries\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":false,\"entries\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeFailed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\"a\",\"code\":\"BROKEN\",\"message\":\"x\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeFailed(
                "{\"requestId\":\"" + REQUEST_ID + "\",\"path\":\"a\",\"message\":\"x\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeFailed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\"a\",\"code\":\"NOT_FOUND\",\"message\":\"x\",\"extra\":1}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeFailed("{\"path\":\"a\",\"code\":\"NOT_FOUND\",\"message\":\"x\"}"));
  }

  /** 共享 ObjectMapper 在 wire 边界拒绝 duplicate field 与 trailing token（顶层与 entry 都覆盖）。 */
  @Test
  void rejectsDuplicateFieldsAndTrailingTokens() {
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeRequest(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\"src\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeRequest(
                "{\"requestId\":\"" + REQUEST_ID + "\",\"path\":\"src\"} trailing"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[{\"name\":\"a\",\"name\":\"a\",\"path\":\"a\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[{\"name\":\"a\",\"path\":\"a\"}]} trailing"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeFailed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\"a\",\"code\":\"NOT_FOUND\",\"message\":\"x\",\"message\":\"x\"}"));
  }

  @Test
  void rejectsBlankOrNonCanonicalListedFields() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, "a", "", ".", false, null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, "a", "a", "..", false, null, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new DaemonDirectoryCodec.DirectoryEntry("a", " "));
    // 失败响应的 path 是请求回显：非法请求路径也必须可归因，只拒绝空白；requestId 仍必须 canonical。
    assertEquals(
        "..",
        new DaemonDirectoryCodec.DirectoryListFailed(
                REQUEST_ID, "..", DaemonDirectoryFailureCode.NOT_FOUND, "x")
            .path());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListFailed(
                "", "..", DaemonDirectoryFailureCode.NOT_FOUND, "x"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListFailed(
                REQUEST_ID, "", DaemonDirectoryFailureCode.NOT_FOUND, "x"));
  }

  /** entry path 必须是请求目录的直接子路径，name 必须等于 path 的最后一段；任何不一致都拒绝。 */
  @Test
  void rejectsInconsistentEntryPathsAndNames() {
    // name 不等于 path 的最后一段。
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDirectoryCodec.DirectoryEntry("other", "src/main"));
    // name 含 '/' 或反斜杠/控制字符，path 非 canonical。
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDirectoryCodec.DirectoryEntry("a/b", "a/b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDirectoryCodec.DirectoryEntry("a\\b", "a\\b"));
    // entry path 不是列表目录的直接子路径（跨层、兄弟路径、root 列表带多段路径）。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID,
                "src",
                "src",
                ".",
                false,
                null,
                List.of(new DaemonDirectoryCodec.DirectoryEntry("main", "src/main/deep"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID,
                "src",
                "src",
                ".",
                false,
                null,
                List.of(new DaemonDirectoryCodec.DirectoryEntry("main", "src2/main"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID,
                ".",
                ".",
                ".",
                false,
                null,
                List.of(new DaemonDirectoryCodec.DirectoryEntry("a", "a/b"))));
    // 合法组合仍然通过（displayPath 是请求 path 的最后一段）。
    new DaemonDirectoryCodec.DirectoryListed(
        REQUEST_ID,
        ".",
        ".",
        ".",
        false,
        null,
        List.of(new DaemonDirectoryCodec.DirectoryEntry("a", "a")));
    new DaemonDirectoryCodec.DirectoryListed(
        REQUEST_ID,
        "src",
        "src",
        ".",
        false,
        null,
        List.of(new DaemonDirectoryCodec.DirectoryEntry("main", "src/main")));
  }

  /** parentPath 必须是 path 的 lexical 父路径：root 与单段为 '.'，多段为去掉最后一段后的前缀。 */
  @Test
  void rejectsParentPathThatIsNotLexicalParent() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, "src", "src", "src", false, null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, "src/main", "main", ".", false, null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, ".", ".", "src", false, null, List.of()));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\"src/main\",\"displayPath\":\"main\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[]}"));
    assertEquals(
        ".",
        new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, "src", "src", ".", false, null, List.of())
            .parentPath());
    assertEquals(
        "src",
        new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, "src/main", "main", "src", false, null, List.of())
            .parentPath());
  }

  /** 超过 MAX_ENTRIES 的 entries 在 record 构造与 decode 入口都拒绝；decode 在物化全部条目前按数组长度拒绝。 */
  @Test
  void rejectsMoreThanMaxEntries() {
    List<DaemonDirectoryCodec.DirectoryEntry> tooMany = new ArrayList<>();
    for (int index = 0; index < DaemonDirectoryCodec.MAX_ENTRIES + 1; index++) {
      String name = "d" + index;
      tooMany.add(new DaemonDirectoryCodec.DirectoryEntry(name, name));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, ".", ".", ".", false, null, tooMany));
    StringBuilder json = new StringBuilder();
    json.append("{\"requestId\":\"")
        .append(REQUEST_ID)
        .append(
            "\",\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":false,\"entries\":[");
    for (int index = 0; index < DaemonDirectoryCodec.MAX_ENTRIES + 1; index++) {
      if (index > 0) {
        json.append(',');
      }
      json.append("{\"name\":\"d")
          .append(index)
          .append("\",\"path\":\"d")
          .append(index)
          .append("\"}");
    }
    json.append("]}");
    assertThrows(DaemonProtocolException.class, () -> codec.decodeListed(json.toString()));
  }

  /** displayPath 必须等于请求 path 的最后一段（root 为 '.'）；旧/恶意 daemon 泄漏本地绝对路径在 codec 层严格拒绝。 */
  @Test
  void rejectsDisplayPathThatIsNotTheLastSegmentOfPath() {
    // record 构造入口：绝对路径与任何不一致值都拒绝。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, ".", "/home/dev/project", ".", false, null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, "src", "/home/dev/project/src", ".", false, null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, "src", "src/main", ".", false, null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, "src/main", "src", "src", false, null, List.of()));
    // wire decode 入口：旧/恶意 daemon 的 DIRECTORY_LISTED 同样严格拒绝。
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\".\",\"displayPath\":\"/home/dev/project\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"requestId\":\""
                    + REQUEST_ID
                    + "\",\"path\":\"src\",\"displayPath\":\"/home/dev/project/src\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[]}"));
    // 合法组合：root 为 '.'，其余是请求 path 的最后一段。
    assertEquals(
        ".",
        new DaemonDirectoryCodec.DirectoryListed(REQUEST_ID, ".", ".", ".", false, null, List.of())
            .displayPath());
    assertEquals(
        "main",
        new DaemonDirectoryCodec.DirectoryListed(
                REQUEST_ID, "src/main", "main", "src", false, null, List.of())
            .displayPath());
  }

  private static void assertInvalidPath(String path) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDirectoryCodec.ListDirectoryRequest(REQUEST_ID, path));
  }
}
