package fun.fengwk.kkstudio.harness.environment.server;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 一条已进入 IDE 通道的命令：等待 Daemon 的 {@code IDE_RESULT} 载荷。
 *
 * <p>Server 只负责准入、在途登记与回执匹配，载荷的编解码属于 IDE 应用。{@code identity} 是命令进入传输时该连接的代际快照；{@code reply}
 * 只会在收到匹配回执、连接代际关闭（{@link EnvironmentCapabilitySendUncertainException}）或调用方主动 {@link
 * EnvironmentDaemonServer#abandonIde(UUID)} 后超时取消三种情况之一完成；IDE 命令不重放。
 */
public record DaemonIdeCall(
    DaemonConnectionIdentity identity, UUID invocationId, CompletableFuture<String> reply) {

  public DaemonIdeCall {
    identity = Objects.requireNonNull(identity, "identity");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    reply = Objects.requireNonNull(reply, "reply");
  }
}
