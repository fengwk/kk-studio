package fun.fengwk.kkstudio.platform.ai.environment.registry;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.platform.ai.environment.gateway.EnvironmentDaemonConnection;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 以 canonical {@link EnvironmentName} 为键的服务端内存 Environment 条目快照。
 *
 * <p>{@code name} 是唯一逻辑路由身份：HELLO 声称、branch 持久化、查询投影与远程调用全部使用同一个名称。Environment 工具由 {@link
 * EnvironmentToolCatalog} 固定；READY 只发布 daemon 的版本化能力对象（environment metadata + skills + MCP server
 * 摘要）。CONNECTING 可以暂时没有 capabilities，READY 必须持有非 null capabilities。
 */
public record LiveEnvironment(
    EnvironmentName name,
    LiveEnvironmentStatus status,
    EnvironmentDaemonConnection connection,
    DaemonCapabilities capabilities,
    Instant lastSeenAt) {

  public LiveEnvironment {
    name = Objects.requireNonNull(name, "name");
    status = Objects.requireNonNull(status, "status");
    connection = Objects.requireNonNull(connection, "connection");
    if (status == LiveEnvironmentStatus.READY) {
      capabilities = Objects.requireNonNull(capabilities, "READY capabilities");
    }
    lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
  }

  public List<ToolDescriptor> tools() {
    return EnvironmentToolCatalog.descriptors();
  }

  /** READY 能力对象中的 skill 摘要。 */
  public List<DaemonSkillDescriptor> skills() {
    return capabilities == null ? List.of() : capabilities.skills();
  }

  /** READY 能力对象中的 MCP server 摘要。 */
  public List<DaemonMcpServerDescriptor> mcpServers() {
    return capabilities == null ? List.of() : capabilities.mcpServers();
  }

  /** daemon 实际 canonical Environment Root 的展示路径（仅 READY 发布；只读披露，不参与路径解析）。 */
  public String rootPath() {
    return capabilities == null ? null : capabilities.environment().rootPath();
  }

  /** 可用性规则：READY + 连接仍打开 + 心跳未超过 {@code heartbeatTimeout} 过期。调用方必须使用与注册表相同的时钟。 */
  public boolean isReady(Instant now, Duration heartbeatTimeout) {
    return status == LiveEnvironmentStatus.READY
        && connection.isOpen()
        && !lastSeenAt.isBefore(Objects.requireNonNull(now, "now").minus(heartbeatTimeout));
  }
}
