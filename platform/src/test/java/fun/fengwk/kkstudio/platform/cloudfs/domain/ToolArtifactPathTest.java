package fun.fengwk.kkstudio.platform.cloudfs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;

import java.util.UUID;

/** {@link ToolArtifactPath} 格式化与解析单元测试。 */
class ToolArtifactPathTest {

  private static final UUID THREAD_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID INVOCATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  /** 验证使用精确 "txt" 扩展名与合法 UUID 格式化和解析 Tool Artifact 虚拟路径。 */
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

    // Coordinates equals, hashCode, toString
    ToolArtifactPath.ToolArtifactCoordinates same =
        new ToolArtifactPath.ToolArtifactCoordinates(THREAD_ID, INVOCATION_ID, "txt");
    assertEquals(parsed, same);
    assertEquals(parsed.hashCode(), same.hashCode());
    assertNotEquals(parsed, null);
    assertNotEquals(parsed, "other");
    assertTrue(parsed.toString().contains(THREAD_ID.toString()));
  }

  /** 验证使用精确 "json" 扩展名与合法 UUID 格式化和解析 Tool Artifact 虚拟路径。 */
  @Test
  void testFormatAndParseValidJsonArtifact() {
    CloudPath path = ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, "json");
    assertEquals(
        "/.artifacts/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.json",
        path.value());

    assertTrue(ToolArtifactPath.isToolArtifactPath(path));

    ToolArtifactPath.ToolArtifactCoordinates parsed = ToolArtifactPath.parse(path);
    assertEquals(THREAD_ID, parsed.threadId());
    assertEquals(INVOCATION_ID, parsed.invocationId());
    assertEquals("json", parsed.extension());
  }

  /** 验证 format 严格拒绝非精确扩展名（带点、大写、前后空格、非 txt/json 等）。 */
  @Test
  void testFormatRejectsNonExactExtensions() {
    // Leading dot is not stripped
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, ".txt"));
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, ".json"));

    // Uppercase is not converted
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, "TXT"));
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, "JSON"));

    // Whitespace is not trimmed
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, "txt "));
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, ""));

    // Other extensions are rejected
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, "bin"));
    assertThrows(
        CloudPathValidationException.class,
        () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, "md"));
  }

  /** 验证 format 方法对必填参数的非空约束。 */
  @Test
  void testFormatRejectsNullInputs() {
    assertThrows(
        NullPointerException.class, () -> ToolArtifactPath.format(null, INVOCATION_ID, "txt"));
    assertThrows(NullPointerException.class, () -> ToolArtifactPath.format(THREAD_ID, null, "txt"));
    assertThrows(
        NullPointerException.class, () -> ToolArtifactPath.format(THREAD_ID, INVOCATION_ID, null));
  }

  /** 验证路径解析对非 canonical UUID（含大写字母或畸形 UUID）的拦截与判定。 */
  @Test
  void testParseAndIsToolArtifactPathStrictCanonicalUuid() {
    // Uppercase UUID in threadId must be rejected
    CloudPath uppercaseThread =
        CloudPath.of(
            "/.artifacts/tool-results/11111111-1111-1111-1111-11111111111A/22222222-2222-2222-2222-222222222222.txt");
    assertFalse(ToolArtifactPath.isToolArtifactPath(uppercaseThread));
    assertThrows(CloudPathValidationException.class, () -> ToolArtifactPath.parse(uppercaseThread));

    // Uppercase UUID in invocationId must be rejected
    CloudPath uppercaseInv =
        CloudPath.of(
            "/.artifacts/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-22222222222B.txt");
    assertFalse(ToolArtifactPath.isToolArtifactPath(uppercaseInv));
    assertThrows(CloudPathValidationException.class, () -> ToolArtifactPath.parse(uppercaseInv));

    // Malformed UUID strings
    CloudPath malformedThread =
        CloudPath.of(
            "/.artifacts/tool-results/not-a-valid-uuid/22222222-2222-2222-2222-222222222222.txt");
    assertFalse(ToolArtifactPath.isToolArtifactPath(malformedThread));
    assertThrows(CloudPathValidationException.class, () -> ToolArtifactPath.parse(malformedThread));

    // 36-char non-UUID string that throws in UUID.fromString
    CloudPath invalidHexThread =
        CloudPath.of(
            "/.artifacts/tool-results/zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz/22222222-2222-2222-2222-222222222222.txt");
    assertFalse(ToolArtifactPath.isToolArtifactPath(invalidHexThread));
    assertThrows(
        CloudPathValidationException.class, () -> ToolArtifactPath.parse(invalidHexThread));
  }

  /** 验证路径解析对非精确 txt/json 扩展名（如大写、非法类型）及文件名的判定与拦截。 */
  @Test
  void testParseAndIsToolArtifactPathStrictExtension() {
    // Uppercase extension in path
    CloudPath uppercaseExt =
        CloudPath.of(
            "/.artifacts/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.TXT");
    assertFalse(ToolArtifactPath.isToolArtifactPath(uppercaseExt));
    assertThrows(CloudPathValidationException.class, () -> ToolArtifactPath.parse(uppercaseExt));

    // Unsupported extension
    CloudPath binExt =
        CloudPath.of(
            "/.artifacts/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.bin");
    assertFalse(ToolArtifactPath.isToolArtifactPath(binExt));
    assertThrows(CloudPathValidationException.class, () -> ToolArtifactPath.parse(binExt));

    // No extension / no dot
    CloudPath noDot =
        CloudPath.of(
            "/.artifacts/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222");
    assertFalse(ToolArtifactPath.isToolArtifactPath(noDot));
    assertThrows(CloudPathValidationException.class, () -> ToolArtifactPath.parse(noDot));

    // Trailing dot
    CloudPath trailingDot =
        CloudPath.of(
            "/.artifacts/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.");
    assertFalse(ToolArtifactPath.isToolArtifactPath(trailingDot));
    assertThrows(CloudPathValidationException.class, () -> ToolArtifactPath.parse(trailingDot));

    // Invalid prefix segments
    CloudPath wrongSegment0 =
        CloudPath.of(
            "/wrong/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.txt");
    assertFalse(ToolArtifactPath.isToolArtifactPath(wrongSegment0));
    assertThrows(CloudPathValidationException.class, () -> ToolArtifactPath.parse(wrongSegment0));

    CloudPath wrongSegment1 =
        CloudPath.of(
            "/.artifacts/wrong/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.txt");
    assertFalse(ToolArtifactPath.isToolArtifactPath(wrongSegment1));
    assertThrows(CloudPathValidationException.class, () -> ToolArtifactPath.parse(wrongSegment1));

    // Non-artifact path locations
    assertFalse(ToolArtifactPath.isToolArtifactPath(CloudPath.of("/knowledge/file.txt")));
    assertFalse(ToolArtifactPath.isToolArtifactPath(CloudPath.of("/.artifacts")));
    assertFalse(ToolArtifactPath.isToolArtifactPath(null));
    assertThrows(CloudPathValidationException.class, () -> ToolArtifactPath.parse(null));
  }
}
