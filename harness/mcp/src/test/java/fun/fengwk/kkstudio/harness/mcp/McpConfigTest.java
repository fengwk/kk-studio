package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** MCP 配置模型测试：验证校验逻辑与 toString 防泄漏约束。 */
class McpConfigTest {

  /** 验证 StdioMcpConfig 校验必填字段且 toString 不回显敏感命令或环境变量。 */
  @Test
  void stdioConfigValidatesAndHidesSensitiveValuesInToString() {
    StdioMcpConfig config =
        new StdioMcpConfig(
            List.of("node", "/opt/secret/server.js"),
            "/var/app",
            Map.of("API_SECRET_KEY", "super_secret_val"));

    assertThat(config.command()).containsExactly("node", "/opt/secret/server.js");
    assertThat(config.cwd()).isEqualTo("/var/app");
    assertThat(config.env()).containsEntry("API_SECRET_KEY", "super_secret_val");

    String str = config.toString();
    assertThat(str).doesNotContain("secret");
    assertThat(str).doesNotContain("/opt/secret");
    assertThat(str).doesNotContain("super_secret_val");
    assertThat(str).contains("commandArgs=2");

    assertThatThrownBy(() -> new StdioMcpConfig(List.of(), "/var/app", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StdioMcpConfig(List.of(""), "/var/app", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StdioMcpConfig(List.of("node"), "", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 验证 RemoteMcpConfig toString 不回显 header 与凭证信息。 */
  @Test
  void remoteConfigValidatesAndHidesHeadersInToString() {
    RemoteMcpConfig config =
        new RemoteMcpConfig(
            "http://127.0.0.1:8080/mcp", Map.of("Authorization", "Bearer sensitive_token"));

    assertThat(config.url()).isEqualTo("http://127.0.0.1:8080/mcp");
    String str = config.toString();
    assertThat(str).doesNotContain("sensitive_token");
    assertThat(str).doesNotContain("Bearer");
    assertThat(str).contains("headersCount=1");

    assertThatThrownBy(() -> new RemoteMcpConfig("", Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
