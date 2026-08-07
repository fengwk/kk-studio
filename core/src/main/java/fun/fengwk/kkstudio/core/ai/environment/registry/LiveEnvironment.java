package fun.fengwk.kkstudio.core.ai.environment.registry;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 以 canonical {@link EnvironmentId} 为键的服务端内存 Environment 条目快照。
 *
 * <p>{@code name} 是 HELLO 时绑定的展示性元数据；可在不同 id 间复用，绝不参与路由。Environment 工具 由 {@link
 * EnvironmentToolCatalog} 固定；READY 只发布 daemon 的可用 skills。
 */
public record LiveEnvironment(
    EnvironmentId id,
    String name,
    LiveEnvironmentStatus status,
    EnvironmentDaemonConnection connection,
    List<DaemonSkillDescriptor> skills,
    Instant lastSeenAt) {

  public LiveEnvironment {
    id = Objects.requireNonNull(id, "id");
    name = requireNonBlank(name, "name");
    status = Objects.requireNonNull(status, "status");
    connection = Objects.requireNonNull(connection, "connection");
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
  }

  public List<ToolDescriptor> tools() {
    return EnvironmentToolCatalog.descriptors();
  }

  public boolean isReady() {
    return status == LiveEnvironmentStatus.READY && connection.isOpen();
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
