package fun.fengwk.kkstudio.core.ai.environment.registry;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 以 canonical {@link EnvironmentName} 为键的服务端内存 Environment 条目快照。
 *
 * <p>{@code name} 是唯一逻辑路由身份：HELLO 声称、branch 持久化、查询投影与远程调用全部使用同一个名称。Environment 工具由 {@link
 * EnvironmentToolCatalog} 固定；READY 只发布 daemon 的可用 skills。
 */
public record LiveEnvironment(
    EnvironmentName name,
    LiveEnvironmentStatus status,
    EnvironmentDaemonConnection connection,
    List<DaemonSkillDescriptor> skills,
    Instant lastSeenAt) {

  public LiveEnvironment {
    name = Objects.requireNonNull(name, "name");
    status = Objects.requireNonNull(status, "status");
    connection = Objects.requireNonNull(connection, "connection");
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
  }

  public List<ToolDescriptor> tools() {
    return EnvironmentToolCatalog.descriptors();
  }

  /** 可用性规则：READY + 连接仍打开 + 心跳未超过 {@code heartbeatTimeout} 过期。调用方必须使用与注册表相同的时钟。 */
  public boolean isReady(Instant now, Duration heartbeatTimeout) {
    return status == LiveEnvironmentStatus.READY
        && connection.isOpen()
        && !lastSeenAt.isBefore(Objects.requireNonNull(now, "now").minus(heartbeatTimeout));
  }
}
