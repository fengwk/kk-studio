package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.IdeReply;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 一条已进入传输的 IDE 命令句柄：等待 Daemon 的 {@code IDE_RESULT}。
 *
 * <p>{@code identity} 是命令进入传输时该连接背后的 Daemon 实例代际；上层据此把 IDE 入口/流绑定到具体 Daemon 实例，跨进程接管后旧绑定自动作废。{@code
 * reply} 只会在收到匹配回执、连接代际关闭（{@link EnvironmentCapabilitySendUncertainException}）或调用方主动 {@link
 * EnvironmentDaemonServer#abandonIde(UUID)} 后超时取消三种情况之一完成；IDE 命令不重放。
 */
public record IdeRequest(
    DaemonConnectionIdentity identity, UUID invocationId, CompletableFuture<IdeReply> reply) {

  public IdeRequest {
    identity = Objects.requireNonNull(identity, "identity");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    reply = Objects.requireNonNull(reply, "reply");
  }

  public EnvironmentId environmentId() {
    return identity.environmentId();
  }
}
