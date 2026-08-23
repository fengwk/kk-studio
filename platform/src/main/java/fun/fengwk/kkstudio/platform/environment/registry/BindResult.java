package fun.fengwk.kkstudio.platform.environment.registry;

import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonConnection;

import java.util.Objects;

/**
 * {@link LiveEnvironmentRegistry#tryBind} 的类型化绑定结果。
 *
 * <p>HELLO 名称绑定的三种确定性结局：
 *
 * <ul>
 *   <li>{@link Accepted}：该连接成为（或继续持有）此名称的 holder（新占用或同连接幂等重绑）；
 *   <li>{@link Rejected}：名称已被另一个连接持有，且该 holder 连接仍打开、心跳未过期——冲突，不得挤占；
 *   <li>{@link Replaced}：名称的现有 holder 连接已关闭，或心跳已按配置超时过期——registry 已原子切换到新 holder，{@link
 *       #displacedConnection()} 是被替换的旧连接，调用方必须恰好清理/关闭一次（registry 条目 已是新 holder，旧连接的 unregister 是
 *       no-op）。
 * </ul>
 *
 * <p>可接管性的唯一依据是现有 holder 的「连接打开 + lastSeen 租约年龄」：CONNECTING 的新鲜声明（连接打开、心跳 未过期）绝不能被抢走。
 */
public sealed interface BindResult
    permits BindResult.Accepted, BindResult.Rejected, BindResult.Replaced {

  static BindResult accepted() {
    return Accepted.INSTANCE;
  }

  static BindResult rejected() {
    return Rejected.INSTANCE;
  }

  static BindResult replaced(EnvironmentDaemonConnection displacedConnection) {
    return new Replaced(displacedConnection);
  }

  /** 新占用或同连接幂等重绑：调用方正常完成绑定。 */
  final class Accepted implements BindResult {
    private static final Accepted INSTANCE = new Accepted();
  }

  /** 名称被另一条 live（打开 + 心跳未过期）连接持有：typed 冲突。 */
  final class Rejected implements BindResult {
    private static final Rejected INSTANCE = new Rejected();
  }

  /** 旧 holder 已死（连接关闭或租约过期），registry 已原子切换到新 holder。 */
  final class Replaced implements BindResult {
    private final EnvironmentDaemonConnection displacedConnection;

    private Replaced(EnvironmentDaemonConnection displacedConnection) {
      this.displacedConnection = Objects.requireNonNull(displacedConnection, "displacedConnection");
    }

    /** 被替换的旧连接；调用方必须恰好关闭/清理一次（registry 条目已是新 holder）。 */
    public EnvironmentDaemonConnection displacedConnection() {
      return displacedConnection;
    }
  }
}
