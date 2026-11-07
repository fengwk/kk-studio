package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 一条已进入传输的 IDE 命令句柄：等待 Daemon 的 {@code IDE_RESULT}。
 *
 * <p>{@code identity} 是命令进入传输时该连接背后的 Daemon 实例代际；上层据此把 IDE 入口/流绑定到具体 Daemon 实例，跨进程接管后旧绑定自动作废。{@code
 * reply} 在收到匹配回执时完成，或在连接代际关闭时以发送结果不确定异常完成；IDE 命令不重放。
 *
 * <p>调用方等待超时或中断后可调用 {@link EnvironmentDaemonServer#abandonIde(UUID)} 取消观察：该操作不完成 {@code
 * reply}，其后的迟到回执会被忽略。
 */
public record IdeRequest(
    DaemonConnectionIdentity identity, UUID invocationId, CompletableFuture<IdeResult> reply) {

  public IdeRequest {
    identity = Objects.requireNonNull(identity, "identity");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    reply = Objects.requireNonNull(reply, "reply");
  }

  public EnvironmentId environmentId() {
    return identity.environmentId();
  }
}
