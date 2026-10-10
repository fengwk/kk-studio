package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeReply;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 一条已进入传输的 IDE 命令句柄：等待 Daemon 的 {@code IDE_RESULT}。
 *
 * <p>{@code reply} 只会在以下三种情况之一完成：收到匹配回执、连接代际关闭（{@link
 * EnvironmentCapabilitySendUncertainException}）、或调用方主动 {@link
 * EnvironmentDaemonServer#abandonIde(UUID)} 后超时取消（调用方自行判定）。IDE 命令不重放。
 */
public record IdeRequest(
    EnvironmentId environmentId, UUID invocationId, CompletableFuture<IdeReply> reply) {

  public IdeRequest {
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    reply = Objects.requireNonNull(reply, "reply");
  }
}
