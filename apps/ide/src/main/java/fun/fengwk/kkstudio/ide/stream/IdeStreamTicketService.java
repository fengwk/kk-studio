package fun.fengwk.kkstudio.ide.stream;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamMode;

import java.util.Optional;
import java.util.UUID;

/**
 * 反向数据面一次性 ticket 的窄端口。
 *
 * <p>{@code OPEN_STREAM} 命令把 ticket + Server 受控 streamUrl 一并交给 Daemon；Daemon 随后主动连接该 stream
 * endpoint 并消费 ticket。ticket 是 256-bit 随机、一次性、短时、绑定 {@link DaemonConnectionIdentity}（Environment +
 * Daemon 实例 + 路由租约）与转发模式的秘密，不携带注册 token，也不写入 URL 日志。
 *
 * <p>租约失效/owner 变更时调用 {@link #revoke} 作废该代际尚未消费的 ticket 并关闭已建立的流。
 */
public interface IdeStreamTicketService {

  Issued issue(DaemonConnectionIdentity identity, IdeStreamMode mode);

  /** 一次性消费 ticket；不存在/已消费/已过期返回 empty。 */
  Optional<Consumed> consume(String ticket);

  /** 撤销某 Environment 指定租约代际下尚未消费的全部 ticket（租约失效/接管时调用）。 */
  void revoke(EnvironmentId environmentId, UUID leaseToken);

  record Issued(String ticket, IdeStreamMode mode) {}

  record Consumed(DaemonConnectionIdentity identity, IdeStreamMode mode) {

    public EnvironmentId environmentId() {
      return identity.environmentId();
    }
  }
}
