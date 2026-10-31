package fun.fengwk.kkstudio.ide.gateway;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 浏览器入口的视图授权端口：数据面只接受绑定到当前 Environment、Daemon 实例、viewId 与 bindingEpoch 的入口凭据。 */
@FunctionalInterface
public interface IdeDataViewAuthorizer {

  /**
   * 校验一个浏览器入口。
   *
   * @param environmentId URL 中的 Environment
   * @param viewId URL 中的 viewId
   * @param cookieHeader 原始 Cookie 请求头；可为 null
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
