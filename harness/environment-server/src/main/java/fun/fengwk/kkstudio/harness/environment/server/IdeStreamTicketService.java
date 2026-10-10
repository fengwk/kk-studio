package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamMode;

import java.util.Optional;
import java.util.UUID;

/**
 * 反向数据面一次性 ticket 的窄端口。
 *
 * <p>{@code OPEN_STREAM} 命令把 ticket + Server 受控 streamUrl 一并交给 Daemon；Daemon 随后主动连接该 stream
 * endpoint 并消费 ticket。ticket 是 256-bit 随机、一次性、短时、绑定 Environment 与当前路由租约的秘密，不携带注册 token，也不写入 URL
 * 日志——适配层只负责把它拼进受控 endpoint 路径。
 *
 * <p>租约失效/owner 变更时调用 {@link #revoke} 关闭该代际尚未消费的 ticket；已消费的连接由适配层按流生命周期关闭。
 */
public interface IdeStreamTicketService {

  /** 铸造一条新 ticket；同一 Environment 可有多个并存的在途 ticket（多个 view/exchange）。 */
  Issued issue(EnvironmentId environmentId, UUID leaseToken, IdeStreamMode mode);

  /** 一次性消费 ticket；不存在/已消费/已过期返回 empty。 */
  Optional<Consumed> consume(String ticket);

  /** 撤销某 Environment 当前租约下尚未消费的全部 ticket（租约失效/接管时调用）。 */
  void revoke(EnvironmentId environmentId, UUID leaseToken);

  record Issued(String ticket, IdeStreamMode mode) {}

  record Consumed(EnvironmentId environmentId, UUID leaseToken, IdeStreamMode mode) {}
}
