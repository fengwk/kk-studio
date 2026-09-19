package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.util.List;
import java.util.Map;

/**
 * MCP 对外值模型的边界契约测试。
 *
 * <p>这些模型是 Platform/Daemon 共享的边界类型，因此必须锁定「非法输入在边界立即失败」与「敏感连接信息绝不通过 toString 泄漏」两条约定。
 */
class McpValueModelTest {

  /** success/error/text/errorText 各工厂必须产出与自身语义一致的 error 标记，避免调用方按错误的极性分支。 */
  @Test
  void factoryMethodsProduceConsistentErrorFlags() {
    List<ResultContent> contents = List.of(new TextResultContent("payload"));

    assertThat(McpToolCallResult.success(contents, "{\"a\":1}").error()).isFalse();
    assertThat(McpToolCallResult.error(contents, "{\"a\":1}").error()).isTrue();
    assertThat(McpToolCallResult.text("ok").error()).isFalse();
    assertThat(McpToolCallResult.errorText("boom").error()).isTrue();

    assertThat(McpToolCallResult.text("ok").contents())
        .containsExactly(new TextResultContent("ok"));
    assertThat(McpToolCallResult.errorText("boom").contents())
        .containsExactly(new TextResultContent("boom"));
    assertThat(McpToolCallResult.errorText("boom").detailsJson()).isEqualTo("{}");
    assertThat(McpToolCallResult.success(contents, "{\"a\":1}").contents())
        .containsExactly(new TextResultContent("payload"));
  }

  /** detailsJson 必须是合法 JSON object：非 object 与畸形文本都在构造时拒绝，缺失则规范化为空对象。 */
  @Test
  void enforcesJsonObjectDetailsAndNormalizesMissingValue() {
    assertThatThrownBy(() -> new McpToolCallResult(false, List.of(), "[]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new McpToolCallResult(false, List.of(), "not json"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new McpToolCallResult(false, List.of(), "{\"a\":1,\"a\":2}"))
        .as("重复键属于畸形输入，不得按「哪个键胜出」的偶然实现通过")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new McpToolCallResult(false, null, "{}"))
        .isInstanceOf(NullPointerException.class);

    // 缺失详情沿用「缺省为空对象」语义，保证 error/success 结果永远有可解析的 detailsJson
    assertThat(new McpToolCallResult(false, List.of(), null).detailsJson()).isEqualTo("{}");
    assertThat(new McpToolCallResult(false, List.of(), "  ").detailsJson()).isEqualTo("{}");
  }

  /** 工具名是必需身份：空白名称必须在构造时拒绝，描述缺失则规范化为空串。 */
  @Test
  void validatesToolDefinitionIdentityAndNormalizesDescription() {
    assertThatThrownBy(() -> new McpToolDefinition("  ", "d", "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(() -> new McpToolDefinition(null, "d", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new McpToolDefinition("t", "d", "[]"))
        .as("非法 schema 不得降级为字符串通过边界")
        .isInstanceOf(IllegalArgumentException.class);

    McpToolDefinition definition = new McpToolDefinition("t", null, "{\"type\":\"object\"}");
    assertThat(definition.name()).isEqualTo("t");
    assertThat(definition.description()).isEmpty();
    assertThat(definition.inputSchemaJson()).isEqualTo("{\"type\":\"object\"}");
  }

  /** 远端连接参数的 toString 只暴露规模信息：URL 可能内嵌凭证，绝不能被日志顺手回显。 */
  @Test
  void remoteConfigNeverExposesUrlOrHeadersInToString() {
    String url = "https://user:super-secret@example.test/mcp";
    RemoteMcpConfig config =
        new RemoteMcpConfig(url, Map.of("Authorization", "Bearer super-secret-token"));

    assertThat(config.toString())
        .isEqualTo("RemoteMcpConfig[urlLength=" + url.length() + ", headersCount=1]")
        .doesNotContain("super-secret")
        .doesNotContain("example.test")
        .doesNotContain("Authorization");
  }

  /** url 是必需的连接身份，headers 缺失时规范化为空表，避免下游出现 null 分支。 */
  @Test
  void validatesRemoteConfigUrlAndNormalizesHeaders() {
    assertThatThrownBy(() -> new RemoteMcpConfig("  ", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("url");
    assertThatThrownBy(() -> new RemoteMcpConfig(null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);

    RemoteMcpConfig config = new RemoteMcpConfig("https://example.test/mcp", null);
    assertThat(config.headers()).isEmpty();
    assertThat(config.url()).isEqualTo("https://example.test/mcp");
  }
}
