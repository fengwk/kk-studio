package fun.fengwk.kkstudio.platform.cloudfs.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;

/** {@link CloudPath} 虚拟绝对路径规范与边界单元测试。 */
class CloudPathTest {

  /** 验证根路径的基本属性与边界行为。 */
  @Test
  void testRootPath() {
    CloudPath root = CloudPath.of("/");
    assertTrue(root.isRoot());
    assertEquals("/", root.value());
    assertEquals("", root.name());
    assertNull(root.parent());
    assertTrue(root.segments().isEmpty());
    assertFalse(root.isArtifactPath());
    assertEquals(root, CloudPath.root());
  }

  /** 验证合法多级路径的解析、分段及父子层级计算。 */
  @Test
  void testValidNestedPaths() {
    CloudPath path = CloudPath.of("/workspace/src/App.java");
    assertFalse(path.isRoot());
    assertEquals("/workspace/src/App.java", path.value());
    assertEquals("App.java", path.name());
    assertEquals(CloudPath.of("/workspace/src"), path.parent());
    assertEquals(List.of("workspace", "src", "App.java"), path.segments());
    assertFalse(path.isArtifactPath());

    CloudPath singleLevel = CloudPath.of("/knowledge");
    assertEquals(CloudPath.root(), singleLevel.parent());
    assertEquals("knowledge", singleLevel.name());
  }

  /** 验证系统保留的 /.artifacts 路径树识别，且不误判同级其他 dot 路径。 */
  @Test
  void testArtifactPathRecognition() {
    CloudPath artifacts = CloudPath.of("/.artifacts");
    assertTrue(artifacts.isArtifactPath());

    CloudPath toolResult = CloudPath.of("/.artifacts/tool-results/thread-1/inv-1.txt");
    assertTrue(toolResult.isArtifactPath());

    CloudPath otherHidden = CloudPath.of("/.hidden/file.txt");
    assertFalse(otherHidden.isArtifactPath());
  }

  /** 验证 Unicode NFC 标准化强制约束，拒绝 NFD 等非标准化字符。 */
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

  /** 验证路径层级的严格后代判断（isDescendantOf）。 */
  @Test
  void testDescendantHierarchy() {
    CloudPath root = CloudPath.of("/");
    CloudPath parent = CloudPath.of("/a/b");
    CloudPath child = CloudPath.of("/a/b/c/d");
    CloudPath sibling = CloudPath.of("/a/b2");
    CloudPath sameLenDiffSeg = CloudPath.of("/a/c");

    assertTrue(child.isDescendantOf(parent));
    assertTrue(child.isDescendantOf(root));
    assertFalse(parent.isDescendantOf(child));
    assertFalse(child.isDescendantOf(child));
    assertFalse(sibling.isDescendantOf(parent));
    assertFalse(child.isDescendantOf(null));
    assertFalse(root.isDescendantOf(root));
    assertFalse(parent.isDescendantOf(sameLenDiffSeg));
  }

  /** 验证 child 方法在根路径与子路径下的派生及非法子分段拦截。 */
  @Test
  void testChildDerivationAndValidation() {
    CloudPath root = CloudPath.root();
    CloudPath workspace = root.child("workspace");
    assertEquals("/workspace", workspace.value());

    CloudPath subFile = workspace.child("file.txt");
    assertEquals("/workspace/file.txt", subFile.value());

    assertThrows(CloudPathValidationException.class, () -> workspace.child(null));
    assertThrows(CloudPathValidationException.class, () -> workspace.child(""));
    assertThrows(CloudPathValidationException.class, () -> workspace.child("sub/dir"));
    assertThrows(CloudPathValidationException.class, () -> workspace.child("sub\\dir"));
  }

  /** 验证 equals、hashCode、toString 与 compareTo 的契约一致性，以及分段层级全序。 */
  @Test
  void testEqualityAndComparisonContracts() {
    CloudPath p1 = CloudPath.of("/workspace/file.txt");
    CloudPath p2 = CloudPath.of("/workspace/file.txt");
    CloudPath p3 = CloudPath.of("/workspace/other.txt");

    assertEquals(p1, p1);
    assertEquals(p1, p2);
    assertNotEquals(p1, p3);
    assertNotEquals(p1, null);
    assertNotEquals(p1, "/workspace/file.txt");

    assertEquals(p1.hashCode(), p2.hashCode());
    assertEquals("/workspace/file.txt", p1.toString());

    assertTrue(p1.compareTo(p3) < 0);
    assertEquals(0, p1.compareTo(p2));
    assertEquals(0, p1.compareTo(p1));
    assertThrows(NullPointerException.class, () -> p1.compareTo(null));

    // 确定性验证层级全序：/a < /a/b < /a!
    // 旧的 canonical 字符串比较由于 '!' (33) < '/' (47) 会错误判定 /a! < /a/b。
    // 分段层级全序下：/a 是 /a/b 的祖先前缀，所以 /a < /a/b；
    // 比较 /a/b 与 /a! 时首个分段 "a" < "a!"，所以 /a/b < /a!。
    CloudPath a = CloudPath.of("/a");
    CloudPath ab = CloudPath.of("/a/b");
    CloudPath aExcl = CloudPath.of("/a!");

    assertTrue(a.compareTo(ab) < 0, "祖先 /a 必须先于其后代 /a/b");
    assertTrue(ab.compareTo(aExcl) < 0, "分支后代 /a/b 必须先于兄弟 /a!");
    assertTrue(a.compareTo(aExcl) < 0, "/a 必须先于 /a!");

    // 验证反绝对称性与传递性
    assertEquals(-Integer.signum(a.compareTo(ab)), Integer.signum(ab.compareTo(a)));
    assertEquals(-Integer.signum(ab.compareTo(aExcl)), Integer.signum(aExcl.compareTo(ab)));
    assertEquals(-Integer.signum(a.compareTo(aExcl)), Integer.signum(aExcl.compareTo(a)));

    // 验证 equals 与 compareTo 契约一致性：compareTo == 0 当且仅当 equals 为 true
    CloudPath aCopy = CloudPath.of("/a");
    assertEquals(0, a.compareTo(aCopy));
    assertEquals(a, aCopy);
    assertTrue(a.compareTo(ab) < 0);
    assertNotEquals(a, ab);

    // 根节点始终是全序中的最小值
    CloudPath root = CloudPath.root();
    assertTrue(root.compareTo(a) < 0);
    assertTrue(root.compareTo(ab) < 0);
    assertTrue(root.compareTo(aExcl) < 0);
    assertEquals(0, root.compareTo(CloudPath.of("/")));
  }

  /** 验证孤立高/低半区代理对（unpaired surrogate）被严格拒绝，杜绝静默替换字符。 */
  @Test
  void testStrictSurrogateRejection() {
    // Lone high surrogate
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/doc-\uD800.txt"));
    // Lone low surrogate
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/doc-\uDC00.txt"));
    // Inverted surrogate pair (low surrogate before high surrogate)
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/doc-\uDC00\uD800.txt"));
  }

  /** 验证 Unicode C0、C1 以及 DEL 等全部 ISO 控制字符均被严格拦截。 */
  @Test
  void testUnicodeIsoControlRejection() {
    // C0 control character: NUL (U+0000), LF (U+000A), TAB (U+0009)
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/\0/c"));
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/b\n/c"));
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/b\t/c"));

    // DEL (U+007F)
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/\u007F/c"));

    // C1 control characters: U+0080, U+0085 (NEL), U+009F
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/\u0080/c"));
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/\u0085/c"));
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/a/\u009F/c"));
  }

  /** 验证非 ASCII 字符在 UTF-8 字节级边界（分段 255 字节、总长 2048 字节）上的严格计算与拦截。 */
  @Test
  void testNonAsciiByteBoundaries() {
    // 3 bytes per Chinese character: 85 chars = 255 bytes (exactly MAX_SEGMENT_BYTES)
    String exactSeg = "测".repeat(85);
    CloudPath validPath = CloudPath.of("/" + exactSeg);
    assertEquals("/" + exactSeg, validPath.value());

    // 86 chars = 258 bytes (exceeds MAX_SEGMENT_BYTES 255)
    String overflowSeg = "测".repeat(86);
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of("/" + overflowSeg));

    // Path total bytes limit: exactly 2048 bytes
    // 8 segments of 80 Chinese characters (240 bytes each) = 1920 bytes + 8 slashes = 1928 bytes
    String seg80 = "中".repeat(80);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      sb.append('/').append(seg80);
    }
    // 9th segment of 39 Chinese characters (117 bytes) + "ab" (2 bytes) + leading slash (1 byte) =
    // 120 bytes
    // Total = 1928 + 120 = 2048 bytes (valid)
    String seg9 = "中".repeat(39) + "ab";
    String validExact2048 = sb.toString() + "/" + seg9;
    assertEquals(2048, validExact2048.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(validExact2048, CloudPath.of(validExact2048).value());

    // Exceed total limit: 2049 bytes
    String overflow2049 = validExact2048 + "c";
    assertEquals(2049, overflow2049.getBytes(StandardCharsets.UTF_8).length);
    assertThrows(CloudPathValidationException.class, () -> CloudPath.of(overflow2049));
  }

  /** 验证常规非法路径格式（相对路径、尾随斜杠、空段、点段、反斜杠等）的校验拦截。 */
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
  }
}
