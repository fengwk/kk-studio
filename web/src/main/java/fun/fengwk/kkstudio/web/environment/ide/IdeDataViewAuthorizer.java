package fun.fengwk.kkstudio.web.environment.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 浏览器入口的视图授权端口：数据面只接受绑定到当前 Environment、Daemon 实例、viewId 与 bindingEpoch 的入口凭据。
 *
 * <p>生产实现 {@link SessionIdeDataViewAuthorizer} 从浏览器会话登记读取授权。实现必须：
 *
 * <ul>
 *   <li>只解析名为 {@code ide-gateway.session-cookie-name} 的 IDE 会话 cookie，不把 Studio 域其它 cookie 当凭据；
 *   <li>返回 cookie 绑定的 Daemon 实例身份、精确 viewId、正 bindingEpoch 与受管 workspace 集合；
 *   <li>在视图关闭、epoch 换代或 Daemon 实例被接管后返回 empty；不得凭 workspace 路径或请求 Host 推断授权。
 * </ul>
 *
 * <p>HTTP 与 WS 数据入口均要求装配此端口，不保留可缺失实现或旧数据通路。
 */
@FunctionalInterface
public interface IdeDataViewAuthorizer {

  /**
   * 校验一个浏览器入口。
   *
   * @param environmentId URL 中的 Environment
   * @param viewId URL 中的 viewId
   * @param cookieHeader 原始 {@code Cookie} 请求头；可为 null
   * @return 精确匹配的授权；无有效入口时 empty
   */
  Optional<IdeDataViewAccess> authorize(
      EnvironmentId environmentId, UUID viewId, String cookieHeader);

  /** 一条已授权的视图数据入口。 */
  record IdeDataViewAccess(
      DaemonConnectionIdentity controlIdentity,
      UUID viewId,
      long bindingEpoch,
      Set<String> authorizedWorkspaces) {

    public IdeDataViewAccess {
      controlIdentity = Objects.requireNonNull(controlIdentity, "controlIdentity");
      viewId = Objects.requireNonNull(viewId, "viewId");
      if (bindingEpoch <= 0) {
        throw new IllegalArgumentException("bindingEpoch must be positive");
      }
      authorizedWorkspaces = Set.copyOf(authorizedWorkspaces);
    }
  }
}
