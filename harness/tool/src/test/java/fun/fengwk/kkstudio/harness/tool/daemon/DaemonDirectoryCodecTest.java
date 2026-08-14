package fun.fengwk.kkstudio.harness.tool.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** LIST_DIRECTORY 请求/响应 payload 的编解码与严格拒绝契约。 */
class DaemonDirectoryCodecTest {

  private final DaemonDirectoryCodec codec = new DaemonDirectoryCodec();

  @Test
  void roundTripsListDirectoryRequestIncludingRoot() {
    assertEquals(
        new DaemonDirectoryCodec.ListDirectoryRequest("src/main"),
        codec.decodeRequest(
            codec.encodeRequest(new DaemonDirectoryCodec.ListDirectoryRequest("src/main"))));
    assertEquals(
        new DaemonDirectoryCodec.ListDirectoryRequest("."),
        codec.decodeRequest(
            codec.encodeRequest(new DaemonDirectoryCodec.ListDirectoryRequest("."))));
  }

  @Test
  void roundTripsDirectoryListedIncludingOptionalGitBranch() {
    DaemonDirectoryCodec.DirectoryListed listed =
        new DaemonDirectoryCodec.DirectoryListed(
            "src",
            "/home/dev/project/src",
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
        new DaemonDirectoryCodec.DirectoryListed(
            ".", "/home/dev/project", ".", false, null, List.of());
    DaemonDirectoryCodec.DirectoryListed decodedWithout =
        codec.decodeListed(codec.encodeListed(withoutBranch));
    assertEquals(withoutBranch, decodedWithout);
    assertNull(decodedWithout.gitBranch());
  }

  @Test
  void roundTripsDirectoryListFailed() {
    DaemonDirectoryCodec.DirectoryListFailed failed =
        new DaemonDirectoryCodec.DirectoryListFailed(
            "missing", DaemonDirectoryFailureCode.NOT_FOUND, "path does not exist: missing");
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
  }

  @Test
  void rejectsUnknownFieldsAndMalformedListedPayloads() {
    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeRequest("{\"path\":\".\",\"extra\":1}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeRequest("{\"path\":\"\"}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeRequest("{\"extra\":1}"));
    // entry 只允许 {name,path}：extra 字段与旧 displayPath 字段都拒绝。
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"path\":\".\",\"displayPath\":\"/root\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[{\"name\":\"a\",\"path\":\"a\",\"extra\":1}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"path\":\".\",\"displayPath\":\"/root\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[{\"name\":\"a\",\"path\":\"a\",\"displayPath\":\"a\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"path\":\".\",\"displayPath\":\"/root\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[{\"name\":\"a\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"path\":\".\",\"displayPath\":\"/root\",\"parentPath\":\".\",\"truncated\":\"no\","
                    + "\"entries\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"path\":\".\",\"displayPath\":\"/root\",\"parentPath\":\".\",\"entries\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeFailed("{\"path\":\"a\",\"code\":\"BROKEN\",\"message\":\"x\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeFailed("{\"path\":\"a\",\"message\":\"x\"}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeFailed(
                "{\"path\":\"a\",\"code\":\"NOT_FOUND\",\"message\":\"x\",\"extra\":1}"));
  }

  @Test
  void rejectsBlankOrNonCanonicalListedFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDirectoryCodec.DirectoryListed("a", "", ".", false, null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DaemonDirectoryCodec.DirectoryListed("a", "a", "..", false, null, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new DaemonDirectoryCodec.DirectoryEntry("a", " "));
    // 失败响应的 path 是请求回显：非法请求路径也必须可归因，只拒绝空白。
    assertEquals(
        "..",
        new DaemonDirectoryCodec.DirectoryListFailed(
                "..", DaemonDirectoryFailureCode.NOT_FOUND, "x")
            .path());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListFailed(
                "", DaemonDirectoryFailureCode.NOT_FOUND, "x"));
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
                "src",
                "/root/src",
                ".",
                false,
                null,
                List.of(new DaemonDirectoryCodec.DirectoryEntry("main", "src/main/deep"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                "src",
                "/root/src",
                ".",
                false,
                null,
                List.of(new DaemonDirectoryCodec.DirectoryEntry("main", "src2/main"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DaemonDirectoryCodec.DirectoryListed(
                ".",
                "/root",
                ".",
                false,
                null,
                List.of(new DaemonDirectoryCodec.DirectoryEntry("a", "a/b"))));
    // 合法组合仍然通过。
    new DaemonDirectoryCodec.DirectoryListed(
        ".", "/root", ".", false, null, List.of(new DaemonDirectoryCodec.DirectoryEntry("a", "a")));
    new DaemonDirectoryCodec.DirectoryListed(
        "src",
        "/root/src",
        ".",
        false,
        null,
        List.of(new DaemonDirectoryCodec.DirectoryEntry("main", "src/main")));
  }

  private static void assertInvalidPath(String path) {
    assertThrows(
        IllegalArgumentException.class, () -> new DaemonDirectoryCodec.ListDirectoryRequest(path));
  }
}
