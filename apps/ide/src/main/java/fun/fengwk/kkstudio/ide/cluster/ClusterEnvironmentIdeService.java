package fun.fengwk.kkstudio.ide.cluster;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.EnvironmentIdeService;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;

import java.util.Objects;

/**
 * {@link EnvironmentIdeService} 的跨节点组合实现：按当前 owner 快照决定本地交付还是单跳内部转发，不改变 PG 不含字节设计。
 *
 * <p>本地 owner 直接调用同一 JVM 的 {@link EnvironmentIdeServiceImpl}（不会递归回组合实现）；远端 owner 最多一次认证 HTTP 转发到目标的
 * 内部 REST，绝不自动重放，失败即上抛不可用。无 READY/租约、注册过期或本节点缺少内部配置时 {@link ClusterNodeRegistry#route} 返回空， 请求在任何
 * Daemon 副作用之前明确不可用。
 */
public final class ClusterEnvironmentIdeService implements EnvironmentIdeService {

  private final ClusterNodeRegistry registry;
  private final IdeClusterClient client;
  private final EnvironmentIdeServiceImpl local;

  public ClusterEnvironmentIdeService(
      ClusterNodeRegistry registry, IdeClusterClient client, EnvironmentIdeServiceImpl local) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.client = Objects.requireNonNull(client, "client");
    this.local = Objects.requireNonNull(local, "local");
  }

  @Override
  public EnvironmentIdeViewDTO openView(
      EnvironmentId environmentId, EnvironmentIdeOpenViewDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    ClusterOwnerRoute route = requireRoute(environmentId);
    if (route.local()) {
      return local.openView(environmentId, request);
    }
    return client.openView(environmentId, route, request);
  }

  @Override
  public EnvironmentIdeViewDTO changeDirectory(
      EnvironmentId environmentId, String viewId, EnvironmentIdeChangeDirectoryDTO request) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(request, "request");
    String parsedViewId = parseViewId(viewId);
    ClusterOwnerRoute route = requireRoute(environmentId);
    if (route.local()) {
      return local.changeDirectory(environmentId, parsedViewId, request);
    }
    return client.changeDirectory(environmentId, route, parsedViewId, request);
  }

  @Override
  public void focusTerminal(EnvironmentId environmentId, String viewId) {
    Objects.requireNonNull(environmentId, "environmentId");
    String parsedViewId = parseViewId(viewId);
    ClusterOwnerRoute route = requireRoute(environmentId);
    if (route.local()) {
      local.focusTerminal(environmentId, parsedViewId);
      return;
    }
    client.focusTerminal(environmentId, route, parsedViewId);
  }

  @Override
  public void closeView(EnvironmentId environmentId, String viewId) {
    Objects.requireNonNull(environmentId, "environmentId");
    String parsedViewId = parseViewId(viewId);
    ClusterOwnerRoute route = requireRoute(environmentId);
    if (route.local()) {
      local.closeView(environmentId, parsedViewId);
      return;
    }
    client.closeView(environmentId, route, parsedViewId);
  }

  private ClusterOwnerRoute requireRoute(EnvironmentId environmentId) {
    return registry
        .route(environmentId)
        .orElseThrow(
            () ->
                new AiEnvironmentIdeUnavailableException(
                    "environment IDE owner route is unavailable"));
  }

  private static String parseViewId(String viewId) {
    try {
      return IdeCommand.validateViewId(viewId);
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new AiValidationException("environment", "viewId must be a canonical UUID");
    }
  }
}
