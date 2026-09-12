package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link ToolArgumentAliasNormalizer} 的参数别名归一化单元测试。 */
class ToolArgumentAliasNormalizerTest {

  /** allowlist 中的全部十个内建文件工具均能将单个 file 别名重写为 path。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "read",
        "write",
        "edit",
        "find",
        "grep",
        "cloud_read",
        "cloud_write",
        "cloud_edit",
        "cloud_find",
        "cloud_grep"
      })
  void rewritesFileForAllowlistTools(String toolName) {
    String input = "{\"file\":\"foo.txt\"}";
    assertEquals("{\"path\":\"foo.txt\"}", ToolArgumentAliasNormalizer.normalize(toolName, input));
  }

  /** 三个候选别名（file、filePath、file_path）在单独出现时均能正确重写为 path。 */
  @ParameterizedTest
  @ValueSource(strings = {"file", "filePath", "file_path"})
  void rewritesEachSingleAliasToCanonicalPath(String alias) {
    String input = String.format("{\"%s\":\"foo.txt\"}", alias);
    assertEquals("{\"path\":\"foo.txt\"}", ToolArgumentAliasNormalizer.normalize("read", input));
  }

  /** 非 allowlist 工具不做任何别名改写。 */
  @ParameterizedTest
  @ValueSource(strings = {"search", "process.exec", "bash", "random_tool"})
  void leavesNonAllowlistToolsUnchanged(String toolName) {
    String input = "{\"file\":\"foo.txt\"}";
    assertSame(input, ToolArgumentAliasNormalizer.normalize(toolName, input));
  }

  /** canonical path 已存在时完全不改动，残留 alias 交由后续 schema 校验。 */
  @Test
  void leavesArgumentsUnchangedWhenCanonicalPathExists() {
    String withFile = "{\"file\":\"other.txt\",\"path\":\"foo.txt\"}";
    String withFilePath = "{\"filePath\":\"other.txt\",\"path\":\"foo.txt\"}";
    String withSnake = "{\"file_path\":\"other.txt\",\"path\":\"foo.txt\"}";

    assertSame(withFile, ToolArgumentAliasNormalizer.normalize("read", withFile));
    assertSame(withFilePath, ToolArgumentAliasNormalizer.normalize("read", withFilePath));
    assertSame(withSnake, ToolArgumentAliasNormalizer.normalize("read", withSnake));
  }

  /** 同时出现多个 alias 时不作猜测，保持原样。 */
  @Test
  void leavesArgumentsUnchangedWhenMultipleAliasesPresent() {
    String multiple1 = "{\"file\":\"a.txt\",\"filePath\":\"b.txt\"}";
    String multiple2 = "{\"file\":\"a.txt\",\"file_path\":\"b.txt\"}";
    String multiple3 = "{\"filePath\":\"a.txt\",\"file_path\":\"b.txt\"}";
    String multipleAll = "{\"file\":\"a.txt\",\"filePath\":\"b.txt\",\"file_path\":\"c.txt\"}";

    assertSame(multiple1, ToolArgumentAliasNormalizer.normalize("read", multiple1));
    assertSame(multiple2, ToolArgumentAliasNormalizer.normalize("read", multiple2));
    assertSame(multiple3, ToolArgumentAliasNormalizer.normalize("read", multiple3));
    assertSame(multipleAll, ToolArgumentAliasNormalizer.normalize("read", multipleAll));
  }

  /** 不改变值本身：不 trim、不转字符串，保留原始节点。 */
  @Test
  void preservesOriginalValueWithoutTrimmingOrConversion() {
    String untrimmed = "{\"file\":\"  a.txt  \"}";
    assertEquals(
        "{\"path\":\"  a.txt  \"}", ToolArgumentAliasNormalizer.normalize("read", untrimmed));

    String numeric = "{\"file\":123}";
    assertEquals("{\"path\":123}", ToolArgumentAliasNormalizer.normalize("read", numeric));

    String bool = "{\"file\":true}";
    assertEquals("{\"path\":true}", ToolArgumentAliasNormalizer.normalize("read", bool));

    String nullVal = "{\"file\":null}";
    assertEquals("{\"path\":null}", ToolArgumentAliasNormalizer.normalize("read", nullVal));
  }

  /** 保留对象中的其他已有字段。 */
  @Test
  void preservesOtherPropertiesWhenMovingAlias() {
    String input = "{\"file\":\"a.txt\",\"workdir\":\"/srv\",\"offset\":10}";
    assertEquals(
        "{\"workdir\":\"/srv\",\"offset\":10,\"path\":\"a.txt\"}",
        ToolArgumentAliasNormalizer.normalize("read", input));
  }

  /** 无 alias 或空对象时不作改写并返回原实例。 */
  @Test
  void returnsSameInstanceWhenNoAliasPresent() {
    String empty = "{}";
    assertSame(empty, ToolArgumentAliasNormalizer.normalize("read", empty));

    String noAlias = "{\"workdir\":\"/srv\"}";
    assertSame(noAlias, ToolArgumentAliasNormalizer.normalize("read", noAlias));
  }

  /** toolName 或 argumentsJson 为空时安全返回原输入。 */
  @Test
  void handlesNullSafely() {
    String input = "{\"file\":\"a.txt\"}";
    assertSame(input, ToolArgumentAliasNormalizer.normalize(null, input));
    assertSame(null, ToolArgumentAliasNormalizer.normalize("read", null));
  }
}
