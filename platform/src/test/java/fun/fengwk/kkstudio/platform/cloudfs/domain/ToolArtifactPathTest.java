package fun.fengwk.kkstudio.platform.cloudfs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;

import java.util.UUID;

/** {@link ToolArtifactPath} 格式化与解析单元测试。 */
class ToolArtifactPathTest {

  private static final UUID THREAD_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID INVOCATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  void testFormatAndParseValidTxtArtifact() {
    CloudPath path = ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, "txt");
    assertEquals(
        "/.artifacts/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.txt",
        path.value());

    assertTrue(ToolArtifactPath.isToolArtifactPath(path));

    ToolArtifactPath.ToolArtifactCoordinates parsed = ToolArtifactPath.parse(path);
    assertEquals(THREAD_ID, parsed.threadId());
    assertEquals(INVOCATION_ID, parsed.invocationId());
    assertEquals("txt", parsed.extension());
  }

  @Test
  void testFormatAndParseValidJsonArtifact() {
    CloudPath path = ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, ".JSON");
    assertEquals(
        "/.artifacts/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.json",
        path.value());

    ToolArtifactPath.ToolArtifactCoordinates parsed = ToolArtifactPath.parse(path);
    assertEquals(THREAD_ID, parsed.threadId());
    assertEquals(INVOCATION_ID, parsed.invocationId());
    assertEquals("json", parsed.extension());
  }

  @Test
  void testFormatInvalidInputs() {
    assertThrows(
        NullPointerException.class, () -> ToolArtifactPath.format(null, INVOCATION_ID, "txt"));
    assertThrows(NullPointerException.class, () -> ToolArtifactPath.format(THREAD_ID, null, "txt"));
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, "invalid/ext"));
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, "invalid\\ext"));

    // Blank or null extension defaults to "txt"
    CloudPath defaultExt = ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, "");
    assertEquals("txt", ToolArtifactPath.parse(defaultExt).extension());
  }

  @Test
  void testParseInvalidPaths() {
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.parse(CloudPath.of("/knowledge/doc.txt")));
    assertThrows(
        CloudPathValidationException.class,
        () ->
            ToolArtifactPath.parse(CloudPath.of("/.artifacts/tool-results/invalid-uuid/inv.txt")));
    assertFalse(ToolArtifactPath.isToolArtifactPath(CloudPath.of("/knowledge/file.txt")));
    assertFalse(ToolArtifactPath.isToolArtifactPath(CloudPath.of("/.artifacts")));
  }
}
