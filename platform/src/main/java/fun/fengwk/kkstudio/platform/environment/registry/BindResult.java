package fun.fengwk.kkstudio.platform.environment.registry;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link LiveEnvironmentRegistry#tryAcquire} 的类型化绑定结果。
 *
 * <ul>
 *   <li>{@link Acquired}：该连接获得此名称的路由持有权（新生成 routeToken）；
 *   <li>{@link RetryLater}：活跃路由已被相同 daemonId 实例持有，daemon 需稍后重试；
 *   <li>{@link Conflict}：活跃路由已被不同 daemonId 实例持有，终态冲突（ENVIRONMENT_NAME_CONFLICT）。
 * </ul>
 */
public sealed interface BindResult
    permits BindResult.Acquired, BindResult.RetryLater, BindResult.Conflict {

  record Acquired(UUID routeToken) implements BindResult {
    public Acquired {
      Objects.requireNonNull(routeToken, "routeToken");
    }
  }

  record RetryLater(String message) implements BindResult {
    public RetryLater {
      Objects.requireNonNull(message, "message");
    }
  }

  record Conflict(String message) implements BindResult {
    public Conflict {
      Objects.requireNonNull(message, "message");
    }
  }

  static BindResult acquired(UUID routeToken) {
    return new Acquired(routeToken);
  }

  static BindResult retryLater(String message) {
    return new RetryLater(message);
  }

  static BindResult conflict(String message) {
    return new Conflict(message);
  }
}
