package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

import java.util.Objects;
import java.util.Optional;

/**
 * IDE 凭据所绑定的 Daemon 实例代际，相对本节点当前 READY 连接的状态。
 *
 * <p>数据面与入口统一据此裁决：{@link #REPLACED} 的凭据永久失效；{@link #DISCONNECTED} 的凭据仍然有效，只是此刻无法向 Daemon
 * 下发命令（同一实例重连后继续）；只有 {@link #CURRENT} 才能建立新的反向流。
 */
public enum IdeGenerationState {

  /** 绑定的实例正是本节点当前的 READY 连接。 */
  CURRENT,

  /** 本节点暂无 READY 连接：同一实例重连后凭据继续有效。 */
  DISCONNECTED,

  /** 另一个 Daemon 实例已在本节点接管：凭据永久失效。 */
  REPLACED;

  public static IdeGenerationState of(
      DaemonConnectionIdentity generation, Optional<DaemonConnectionIdentity> current) {
    Objects.requireNonNull(generation, "generation");
    if (current.isEmpty()) {
      return DISCONNECTED;
    }
    return current.get().equals(generation) ? CURRENT : REPLACED;
  }

  /** 按本节点当前 READY 连接裁决 {@code generation} 的状态。 */
  public static IdeGenerationState of(
      IdeCommandTransport transport, DaemonConnectionIdentity generation) {
    return of(generation, transport.connectionIdentity(generation.environmentId()));
  }
}
