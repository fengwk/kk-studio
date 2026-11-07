package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 一条已进入 IDE 通道的命令：等待 Daemon 的 {@code IDE_RESULT} 载荷。
 *
 * <p>{@code reply} 在收到匹配回执时完成，或在连接代际关闭时以发送结果不确定异常完成；IDE 命令不重放。{@link
 * EnvironmentDaemonServer#abandonIde(UUID)} 只取消观察，不完成 {@code reply}，其后的迟到回执会被忽略。
 */
public record DaemonIdeCall(
    DaemonConnectionIdentity identity, UUID invocationId, CompletableFuture<IdeResult> reply) {

  public DaemonIdeCall {
    identity = Objects.requireNonNull(identity, "identity");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    reply = Objects.requireNonNull(reply, "reply");
  }
}
