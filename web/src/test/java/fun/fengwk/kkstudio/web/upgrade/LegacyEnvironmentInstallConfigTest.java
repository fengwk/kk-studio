package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 离线转换删除 {@code environment.install_config.javaHome} 的纯逻辑边界：只有受支持的顶层对象形状才允许改写，其余持久化值一律 fail closed，
 * 且失败只暴露固定分类与固定原因。
 *
 * <p>它只钉真实 PG 升级路径难以穷举的输入形状；真实列顺序与提交语义仍由 {@code *PostgresIntegrationTest} 覆盖。
 */
class LegacyEnvironmentInstallConfigTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 只能出现在非法输入里的标记：任何被回显到 message 的路径都会让用例失败。 */
  private static final String UNTRUSTED = "untrusted-value-marker";

  /** 真实安装配置形状：顶层已退役的 {@code javaHome} 必须删除，嵌套的合法 LSP 环境变量 {@code JAVA_HOME} 必须保留。 */
  private static final String SOURCE_CONFIG =
      "{\"operatingSystem\":\"linux\","
          + "\"javaHome\":\"/opt/jdk-21\","
          + "\"daemon\":{\"studioUrl\":\"https://legacy-studio.example.invalid/\","
          + "\"note\":\"host\","
          + "\"lsp\":{\"servers\":{\"jdtls\":{\"command\":[\"jdtls\"],"
          + "\"env\":{\"JAVA_HOME\":\"/opt/jdtls-jdk\"}}}}}}";

  private static final String FIXED_REASON =
      "legacy environment install config is not the exact supported source shape";

  /** 合法配置只删除顶层 javaHome：operatingSystem/daemon 值语义保真，嵌套 JAVA_HOME 不做全局替换。 */
  @Test
  void validConfigDropsOnlyTheTopLevelJavaHome() {
    ObjectNode expected = (ObjectNode) MAPPER.readTree(SOURCE_CONFIG);
    expected.remove("javaHome");

    String converted = LegacyEnvironmentInstallConfig.withoutJavaHome(SOURCE_CONFIG);

    JsonNode actual = MAPPER.readTree(converted);
    assertEquals(expected, actual);
    assertEquals("linux", actual.at("/operatingSystem").asText());
    assertEquals("https://legacy-studio.example.invalid/", actual.at("/daemon/studioUrl").asText());
    assertEquals("host", actual.at("/daemon/note").asText());
    assertEquals("/opt/jdtls-jdk", actual.at("/daemon/lsp/servers/jdtls/env/JAVA_HOME").asText());
    // 源确实携带 javaHome：转换结果不是靠空输入伪造出来的。
    assertEquals("/opt/jdk-21", MAPPER.readTree(SOURCE_CONFIG).get("javaHome").asText());
  }

  /** 原就没有 javaHome 的对象：其余字段值原样保留。 */
  @Test
  void configWithoutJavaHomeKeepsEveryOtherField() {
    String source =
        "{\"operatingSystem\":\"linux\","
            + "\"daemon\":{\"studioUrl\":\"https://legacy-studio.example.invalid/\"}}";

    assertEquals(
        MAPPER.readTree(source),
        MAPPER.readTree(LegacyEnvironmentInstallConfig.withoutJavaHome(source)));
  }

  /** SQL NULL 原样保留：不会产生空对象或字符串 "null"。 */
  @Test
  void nullStaysNull() {
    assertNull(LegacyEnvironmentInstallConfig.withoutJavaHome(null));
  }

  /** 非受支持来源一律 fail closed：固定 CONVERT 分类、固定原因，不携带输入值、底层 cause 或 suppressed。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("unsupportedSources")
  void unsupportedSourcesFailClosedWithoutLeakingInputOrCause(String label, String json) {
    LegacyDatabaseUpgradeException error =
        assertThrows(
            LegacyDatabaseUpgradeException.class,
            () -> LegacyEnvironmentInstallConfig.withoutJavaHome(json));
    assertEquals(LegacyDatabaseUpgradeException.Stage.CONVERT, error.stage());
    assertEquals(FIXED_REASON, error.getMessage());
    assertNull(error.getCause());
    assertEquals(0, error.getSuppressed().length);
    assertFalse(error.getMessage().contains(UNTRUSTED));
    assertFalse(error.getMessage().contains("javaHome"));
  }

  private static Stream<Arguments> unsupportedSources() {
    return Stream.of(
        Arguments.of("empty text", ""),
        Arguments.of("blank text", "   "),
        Arguments.of("json null", "null"),
        Arguments.of("array", "[{\"javaHome\":\"" + UNTRUSTED + "\"}]"),
        Arguments.of("bare string", "\"" + UNTRUSTED + "\""),
        Arguments.of("bare boolean", "true"),
        Arguments.of("bare number", "42"),
        Arguments.of("truncated object", "{\"operatingSystem\":\"" + UNTRUSTED + "\""),
        Arguments.of("trailing token", "{\"operatingSystem\":\"linux\"} false"),
        Arguments.of(
            "duplicate top-level key",
            "{\"javaHome\":\"" + UNTRUSTED + "\",\"javaHome\":\"/opt/jdk-21\"}"),
        Arguments.of(
            "duplicate nested key",
            "{\"daemon\":{\"studioUrl\":\""
                + UNTRUSTED
                + "\",\"studioUrl\":\"https://s.invalid/\"}}"),
        Arguments.of(
            "concatenated documents",
            "{\"javaHome\":\"" + UNTRUSTED + "\"}{\"javaHome\":\"/opt/jdk-21\"}"),
        Arguments.of("trailing comma", "{\"operatingSystem\":\"" + UNTRUSTED + "\",}"));
  }

  /** row 只按 environment 目标列顺序读取这 7 列，并把 install_config 交给同一转换；顺序漂移或漏掉转换都会失败。 */
  @Test
  void rowProjectsTheSevenEnvironmentColumnsInTargetOrder() throws SQLException {
    ResultSet source = mock(ResultSet.class);
    when(source.getString("id")).thenReturn("0e5a5f27-0f7c-4a3a-9a8f-3a1c2b4d5e6f");
    when(source.getString("name")).thenReturn("legacy-environment");
    when(source.getString("registration_token")).thenReturn("legacy-registration-token");
    when(source.getString("install_config")).thenReturn(SOURCE_CONFIG);
    when(source.getString("created_at")).thenReturn("2025-01-02 03:04:05.678+00");
    when(source.getString("updated_at")).thenReturn(null);
    when(source.getString("version")).thenReturn("3");

    List<String> projected = LegacyEnvironmentInstallConfig.row(source);

    assertEquals(
        Arrays.asList(
            "0e5a5f27-0f7c-4a3a-9a8f-3a1c2b4d5e6f",
            "legacy-environment",
            "legacy-registration-token",
            LegacyEnvironmentInstallConfig.withoutJavaHome(SOURCE_CONFIG),
            "2025-01-02 03:04:05.678+00",
            null,
            "3"),
        projected);
    InOrder order = inOrder(source);
    order.verify(source).getString("id");
    order.verify(source).getString("name");
    order.verify(source).getString("registration_token");
    order.verify(source).getString("install_config");
    order.verify(source).getString("created_at");
    order.verify(source).getString("updated_at");
    order.verify(source).getString("version");
    order.verifyNoMoreInteractions();
  }

  /** 源行没有安装配置时投影保持 SQL NULL，而不是空对象或字符串 "null"。 */
  @Test
  void rowKeepsSqlNullsWithoutInventingValues() throws SQLException {
    ResultSet source = mock(ResultSet.class);
    when(source.getString("install_config")).thenReturn(null);

    assertEquals(
        Arrays.asList(null, null, null, null, null, null, null),
        LegacyEnvironmentInstallConfig.row(source));
  }
}
