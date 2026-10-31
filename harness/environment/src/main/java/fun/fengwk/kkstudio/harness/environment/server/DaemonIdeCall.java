package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.protocol.IdeResult;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 一条已进入 IDE 通道的命令：等待 Daemon 的 {@code IDE_RESULT} 载荷。
 *
 * <p>reply 只在收到匹配回执、连接代际关闭（发送不确定）或调用方 abandonIde 三种情况之一完成；IDE 命令不重放。
 */
public record DaemonIdeCall(
    DaemonConnectionIdentity identity, UUID invocationId, CompletableFuture<IdeResult> reply) {

  public DaemonIdeCall {
    identity = Objects.requireNonNull(identity, "identity");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    reply = Objects.requireNonNull(reply, "reply");
  }
}
