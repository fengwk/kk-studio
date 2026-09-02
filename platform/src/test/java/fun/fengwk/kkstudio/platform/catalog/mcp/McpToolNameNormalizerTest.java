package fun.fengwk.kkstudio.platform.catalog.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

/**
 * MCP 工具名规范化器测试：确保覆盖 100% 规则分支。
 *
 * <p>规范化规则：
 *
 * <ul>
 *   <li>lowercase 转小写
 *   <li>所有非 [a-z0-9] 字符替换为 '_'
 *   <li>连续 '_' 合并为单个 '_'
 *   <li>去除首尾 '_'
 *   <li>最终结果必须 ≤64 字符且符合 ToolDescriptor name 语法
 *   <li>绝不追加 hash 后缀，超长直接报错
 * </ul>
 */
class McpToolNameNormalizerTest {

  @Test
  void normalizesBasicSourceToolNames() {
    // 意图：验证大写转小写、特殊字符转下划线、下划线合并与首尾裁剪
    assertEquals("searchfiles", McpToolNameNormalizer.normalizeSourceToolName("searchFiles"));
    assertEquals("search_files", McpToolNameNormalizer.normalizeSourceToolName("search-files"));
    assertEquals("search_files", McpToolNameNormalizer.normalizeSourceToolName("Search_Files"));
    assertEquals(
        "search_files", McpToolNameNormalizer.normalizeSourceToolName("__search__files__"));
    assertEquals("search_files", McpToolNameNormalizer.normalizeSourceToolName("search.files"));
    assertEquals("search_files", McpToolNameNormalizer.normalizeSourceToolName("search@#$%files"));
    assertEquals("tool_123", McpToolNameNormalizer.normalizeSourceToolName("Tool-123!"));
    assertEquals("123", McpToolNameNormalizer.normalizeSourceToolName("123"));
  }

  @Test
  void normalizesConsistentlyAcrossLocales() {
    // 意图：验证在 Turkish 等特殊默认 Locale 环境下，小写转换依然使用 Locale.ROOT 保证确定性
    Locale defaultLocale = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertEquals("info_test", McpToolNameNormalizer.normalizeSourceToolName("INFO_TEST"));
      assertEquals("title", McpToolNameNormalizer.normalizeSourceToolName("TITLE"));
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t\n", "___", "--", "@#$%"})
  void rejectsBlankOrEmptyNormalization(String invalid) {
    // 意图：验证空白或规范化后为空的工具名抛出 IllegalArgumentException
    assertThrows(
        IllegalArgumentException.class,
        () -> McpToolNameNormalizer.normalizeSourceToolName(invalid));
  }

  @Test
  void rejectsNullSourceToolName() {
    // 意图：验证 null sourceToolName 抛出 IllegalArgumentException
    assertThrows(
        IllegalArgumentException.class, () -> McpToolNameNormalizer.normalizeSourceToolName(null));
  }

  @Test
  void buildsModelToolNameCorrectly() {
    // 意图：验证 mcp_<server>_<normalized> 拼接规则
    assertEquals(
        "mcp_github_searchrepos", McpToolNameNormalizer.modelToolName("github", "searchRepos"));
    assertEquals(
        "mcp_brave_search_web_search",
        McpToolNameNormalizer.modelToolName("brave_search", "web-search"));
  }

  @Test
  void rejectsInvalidServerNameInModelToolName() {
    // 意图：验证 server 名不符合 ^[a-z][a-z0-9_]*$ 时抛出异常
    assertThrows(
        IllegalArgumentException.class,
        () -> McpToolNameNormalizer.modelToolName("GitHub", "search"));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpToolNameNormalizer.modelToolName("123server", "search"));
    assertThrows(
        IllegalArgumentException.class,
        () -> McpToolNameNormalizer.modelToolName("server-dash", "search"));
    assertThrows(
        NullPointerException.class, () -> McpToolNameNormalizer.modelToolName(null, "search"));
  }

  @Test
  void requireModelToolNameEnforcesMax64Length() {
    // 意图：验证 ≤64 字符通过，>64 字符直接抛出且绝不追加 hash
    String server = "srv";
    // "mcp_srv_" = 8 字符，剩余最大 56 字符
    String max56 = "a".repeat(56);
    String valid64 = McpToolNameNormalizer.requireModelToolName(server, max56);
    assertEquals(64, valid64.length());
    assertEquals("mcp_srv_" + max56, valid64);

    String over57 = "a".repeat(57);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> McpToolNameNormalizer.requireModelToolName(server, over57));
    assertTrue(ex.getMessage().contains("exceeds 64 characters"));
  }

  @Test
  void validatesToolDescriptorNameSyntax() {
    // 意图：验证 ToolDescriptor name 语法（字母开头，仅字母数字_-）
    assertTrue(McpToolNameNormalizer.isValidToolDescriptorName("mcp_server_tool"));
    assertTrue(McpToolNameNormalizer.isValidToolDescriptorName("Tool_1"));
    assertTrue(McpToolNameNormalizer.isValidToolDescriptorName("a-b_c"));

    assertFalse(McpToolNameNormalizer.isValidToolDescriptorName(null));
    assertFalse(McpToolNameNormalizer.isValidToolDescriptorName(""));
    assertFalse(McpToolNameNormalizer.isValidToolDescriptorName("123tool"));
    assertFalse(McpToolNameNormalizer.isValidToolDescriptorName("_tool"));
    assertFalse(McpToolNameNormalizer.isValidToolDescriptorName("-tool"));
    assertFalse(McpToolNameNormalizer.isValidToolDescriptorName("tool@name"));
  }
}
