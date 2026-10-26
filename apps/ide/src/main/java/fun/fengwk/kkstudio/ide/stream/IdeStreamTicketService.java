package fun.fengwk.kkstudio.ide.stream;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamMode;

import java.util.Optional;

/**
 * 反向数据面一次性 ticket 的窄端口。
 *
 * <p>{@code OPEN_STREAM} 命令把 ticket + Server 受控 streamUrl 一并交给 Daemon；Daemon 随后主动连接该 stream
 * endpoint 并消费 ticket。ticket 是 256-bit 随机、一次性、短时、绑定 {@link DaemonConnectionIdentity}（Environment +
 * Daemon 实例）与转发模式的秘密，不携带注册 token，也不写入 URL 日志。
 *
 * <p>另一个 Daemon 实例接管时调用 {@link #revoke} 作废旧代际尚未消费的 ticket。
 */
public interface IdeStreamTicketService {

  Issued issue(DaemonConnectionIdentity identity, IdeStreamMode mode);

  /** 一次性消费 ticket；不存在/已消费/已过期返回 empty。 */
  Optional<Consumed> consume(String ticket);

  /** 撤销某代际尚未消费的全部 ticket（另一个 Daemon 实例接管时调用）。 */
  void revoke(DaemonConnectionIdentity identity);

  record Issued(String ticket, IdeStreamMode mode) {}

  record Consumed(DaemonConnectionIdentity identity, IdeStreamMode mode) {

    public EnvironmentId environmentId() {
      return identity.environmentId();
    }
  }
}
