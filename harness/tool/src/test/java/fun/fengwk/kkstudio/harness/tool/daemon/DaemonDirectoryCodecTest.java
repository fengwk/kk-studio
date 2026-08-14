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
            "src",
            ".",
            true,
            "main",
            List.of(
                new DaemonDirectoryCodec.DirectoryEntry("src/main", "main"),
                new DaemonDirectoryCodec.DirectoryEntry("src/test", "test")));
    DaemonDirectoryCodec.DirectoryListed decoded = codec.decodeListed(codec.encodeListed(listed));
    assertEquals(listed, decoded);
    assertTrue(decoded.truncated());
    assertEquals("main", decoded.gitBranch());

    DaemonDirectoryCodec.DirectoryListed withoutBranch =
        new DaemonDirectoryCodec.DirectoryListed(".", ".", ".", false, null, List.of());
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
  }

  @Test
  void rejectsUnknownFieldsAndMalformedListedPayloads() {
    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeRequest("{\"path\":\".\",\"extra\":1}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeRequest("{\"path\":\"\"}"));
    assertThrows(DaemonProtocolException.class, () -> codec.decodeRequest("{\"extra\":1}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[{\"path\":\"a\",\"displayPath\":\"a\",\"extra\":1}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":false,"
                    + "\"entries\":[{\"path\":\"a\"}]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"truncated\":\"no\","
                    + "\"entries\":[]}"));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.decodeListed(
                "{\"path\":\".\",\"displayPath\":\".\",\"parentPath\":\".\",\"entries\":[]}"));
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

  private static void assertInvalidPath(String path) {
    assertThrows(
        IllegalArgumentException.class, () -> new DaemonDirectoryCodec.ListDirectoryRequest(path));
  }
}
