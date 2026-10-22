package fun.fengwk.kkstudio.harness.environment.server;

import java.util.Objects;
import java.util.UUID;

/** HELLO 抢约占位的结果。 */
public sealed interface LeaseBindResult {

  /** 路由租约已归属本节点连接，返回新签发的 {@code leaseToken}。 */
  record Acquired(UUID leaseToken) implements LeaseBindResult {
    public Acquired {
      leaseToken = Objects.requireNonNull(leaseToken, "leaseToken");
    }
  }

  /** 环境被其他活跃连接持有；daemon 应退避重试。 */
  record RetryLater(String message) implements LeaseBindResult {
    public RetryLater {
      message = message == null ? "environment route is actively held" : message;
    }
  }

  /** 注册凭据或环境本身被拒绝；daemon 不得重试。 */
  record Rejected(String message) implements LeaseBindResult {
    public Rejected {
      message = message == null ? "environment registration rejected" : message;
    }
  }
}
