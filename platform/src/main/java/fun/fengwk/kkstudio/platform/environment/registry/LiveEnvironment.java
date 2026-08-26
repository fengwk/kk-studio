package fun.fengwk.kkstudio.platform.environment.registry;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonConnection;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 以 canonical {@link EnvironmentName} 为键的服务端内存 Environment 条目快照。
 *
 * <p>{@code name} 是唯一逻辑路由身份：HELLO 声称、branch 持久化、查询投影与远程调用全部使用同一个名称。Environment 的 atomic
 * capabilities 由共享 capability catalog 固定；READY 只发布 daemon 的版本化能力对象（environment metadata + skills +
 * MCP server 摘要）。CONNECTING 可以暂时没有 daemon capabilities，READY 必须持有非 null daemon capabilities。
 */
public record LiveEnvironment(
    EnvironmentName name,
    LiveEnvironmentStatus status,
    EnvironmentDaemonConnection connection,
    DaemonCapabilities daemonCapabilities,
    Instant lastSeenAt) {

  public LiveEnvironment {
    name = Objects.requireNonNull(name, "name");
    status = Objects.requireNonNull(status, "status");
    connection = Objects.requireNonNull(connection, "connection");
    if (status == LiveEnvironmentStatus.READY) {
      daemonCapabilities = Objects.requireNonNull(daemonCapabilities, "READY daemonCapabilities");
    }
    lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
  }

  /** HELLO 已校验 catalog version 后，所有 live Environment 都支持同一组 atomic capabilities。 */
  public List<EnvironmentCapabilityDescriptor> capabilities() {
    return EnvironmentCapabilityCatalog.descriptors();
  }

  /** READY 能力对象中的 skill 摘要。 */
  public List<DaemonSkillDescriptor> skills() {
    return daemonCapabilities == null ? List.of() : daemonCapabilities.skills();
  }

  /** READY 能力对象中的 MCP server 摘要。 */
  public List<DaemonMcpServerDescriptor> mcpServers() {
    return daemonCapabilities == null ? List.of() : daemonCapabilities.mcpServers();
  }

  /** daemon 实际 canonical Environment Root 的展示路径（仅 READY 发布；只读披露，不参与路径解析）。 */
  public String rootPath() {
    return daemonCapabilities == null ? null : daemonCapabilities.environment().rootPath();
  }

  /** 可用性规则：READY + 连接仍打开 + 心跳未超过 {@code heartbeatTimeout} 过期。调用方必须使用与注册表相同的时钟。 */
  public boolean isReady(Instant now, Duration heartbeatTimeout) {
    return status == LiveEnvironmentStatus.READY
        && connection.isOpen()
        && !lastSeenAt.isBefore(Objects.requireNonNull(now, "now").minus(heartbeatTimeout));
  }
}
