package fun.fengwk.kkstudio.platform.cloudfs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;

import java.text.Normalizer;
import java.util.List;

/** {@link CloudPath} 虚拟绝对路径规范与边界单元测试。 */
class CloudPathTest {

  @Test
  void testRootPath() {
    CloudPath root = CloudPath.of("/");
    assertTrue(root.isRoot());
    assertEquals("/", root.value());
    assertEquals("", root.name());
    assertNull(root.parent());
    assertTrue(root.segments().isEmpty());
    assertFalse(root.isArtifactPath());
  }

  @Test
  void testValidNestedPaths() {
    CloudPath path = CloudPath.of("/workspace/src/App.java");
    assertFalse(path.isRoot());
    assertEquals("/workspace/src/App.java", path.value());
    assertEquals("App.java", path.name());
    assertEquals(CloudPath.of("/workspace/src"), path.parent());
    assertEquals(List.of("workspace", "src", "App.java"), path.segments());
    assertFalse(path.isArtifactPath());
  }

  @Test
  void testArtifactPathRecognition() {
    CloudPath artifacts = CloudPath.of("/.artifacts");
    assertTrue(artifacts.isArtifactPath());

    CloudPath toolResult = CloudPath.of("/.artifacts/tool-results/thread-1/inv-1.txt");
    assertTrue(toolResult.isArtifactPath());

    CloudPath otherHidden = CloudPath.of("/.hidden/file.txt");
    assertFalse(otherHidden.isArtifactPath());
  }

  @Test
  void testNfcNormalization() {
    // e + combining acute accent (decomposed NFD form) must be rejected
    String decomposed = "/cafe\u0301.txt";
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of(decomposed));

    // NFC normalized form succeeds
    String nfc = Normalizer.normalize(decomposed, Normalizer.Form.NFC);
    CloudPath path = CloudPath.of(nfc);
    assertEquals("/café.txt", path.value());
    assertEquals("café.txt", path.name());
  }

  @Test
  void testAncestorAndDescendant() {
    CloudPath root = CloudPath.of("/");
    CloudPath parent = CloudPath.of("/a/b");
    CloudPath child = CloudPath.of("/a/b/c/d");
    CloudPath sibling = CloudPath.of("/a/b2");

    assertTrue(child.isDescendantOf(parent));
    assertTrue(child.isDescendantOf(root));
    assertFalse(parent.isDescendantOf(child));
    assertFalse(child.isDescendantOf(child)); // not strict descendant of itself
    assertFalse(sibling.isDescendantOf(parent));

    assertTrue(parent.isAncestorOf(child));
    assertTrue(root.isAncestorOf(child));
    assertFalse(child.isAncestorOf(parent));
  }

  @Test
  void testInvalidPaths() {
    // Null or empty
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of(null));
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of(""));
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("   "));

    // Relative path
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("relative/path"));

    // Trailing slash
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/workspace/"));

    // Consecutive slashes
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a//b"));

    // Dot and dot-dot
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/./b"));
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/../b"));
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/."));
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/.."));

    // Backslash
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a\\b"));

    // Control characters
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/b\n/c"));
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/\0/c"));

    // Segment UTF-8 limit (255 bytes)
    String longSegment = "a".repeat(256);
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/" + longSegment));

    // Path UTF-8 limit (2048 bytes)
    String deepPath = "/" + "a/".repeat(1025);
    // remove trailing slash for test
    String longPath = deepPath.substring(0, 2049);
    if (!longPath.endsWith("/")) {
      assertThrows(CloudPathValidationException.class, () -> CloudPath.of(longPath));
    }
  }
}
