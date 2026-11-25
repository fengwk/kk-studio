package fun.fengwk.kkstudio.ide.cluster;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.EnvironmentIdeService;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.IdeUuids;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeReadViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewSnapshotDTO;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link EnvironmentIdeService} 的跨节点组合实现：按当前 owner 快照决定本地交付还是单跳内部转发。
 *
 * <p>本地 owner 直接调用同一 JVM 的 {@link EnvironmentIdeServiceImpl}；远端 owner 最多一次认证 HTTP 转发到目标的内部
 * REST，绝不自动重放。 浏览器请求可以落在第三节点，claim 前的事件节点核对在 owner 完成。
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
      EnvironmentId environmentId, EnvironmentIdeOpenViewDTO request, StudioPrincipal principal) {
    Objects.requireNonNull(request, "request");
    return route(
        environmentId,
        () -> local.openView(environmentId, request, principal),
        owner -> client.openView(environmentId, owner, request, principal));
  }

  @Override
  public EnvironmentIdeViewDTO changeDirectory(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeChangeDirectoryDTO request,
      StudioPrincipal principal) {
    Objects.requireNonNull(request, "request");
    String parsedViewId = parseViewId(viewId);
    return route(
        environmentId,
        () -> local.changeDirectory(environmentId, parsedViewId, request, principal),
        owner -> client.changeDirectory(environmentId, owner, parsedViewId, request, principal));
  }

  @Override
  public void focusTerminal(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeViewBindingDTO request,
      StudioPrincipal principal) {
    Objects.requireNonNull(request, "request");
    String parsedViewId = parseViewId(viewId);
    routeAction(
        environmentId,
        () -> local.focusTerminal(environmentId, parsedViewId, request, principal),
        owner -> client.focusTerminal(environmentId, owner, parsedViewId, request, principal));
  }

  @Override
  public void openAttachment(
      EnvironmentId environmentId,
      String viewId,
      String sessionId,
      String resourceId,
      StudioPrincipal principal,
      EnvironmentIdeViewBindingDTO request) {
    Objects.requireNonNull(request, "request");
    String parsedViewId = parseViewId(viewId);
    String parsedSessionId = IdeUuids.requireInput(sessionId, "sessionId").toString();
    String parsedResourceId = IdeUuids.requireInput(resourceId, "resourceId").toString();
    routeAction(
        environmentId,
        () ->
            local.openAttachment(
                environmentId, parsedViewId, parsedSessionId, parsedResourceId, principal, request),
        owner ->
            client.openAttachment(
                environmentId,
                owner,
                parsedViewId,
                parsedSessionId,
                parsedResourceId,
                request,
                principal));
  }

  @Override
  public void collapseMobile(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeViewBindingDTO request,
      StudioPrincipal principal) {
    Objects.requireNonNull(request, "request");
    String parsedViewId = parseViewId(viewId);
    routeAction(
        environmentId,
        () -> local.collapseMobile(environmentId, parsedViewId, request, principal),
        owner -> client.collapseMobile(environmentId, owner, parsedViewId, request, principal));
  }

  @Override
  public void closeView(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeCloseViewDTO request,
      StudioPrincipal principal) {
    Objects.requireNonNull(request, "request");
    String parsedViewId = parseViewId(viewId);
    routeAction(
        environmentId,
        () -> local.closeView(environmentId, parsedViewId, request, principal),
        owner -> client.closeView(environmentId, owner, parsedViewId, request, principal));
  }

  @Override
  public EnvironmentIdeViewSnapshotDTO readView(
      EnvironmentId environmentId,
      String viewId,
      EnvironmentIdeReadViewDTO request,
      StudioPrincipal principal) {
    Objects.requireNonNull(request, "request");
    String parsedViewId = parseViewId(viewId);
    return route(
        environmentId,
        () -> local.readView(environmentId, parsedViewId, request, principal),
        owner -> client.readView(environmentId, owner, parsedViewId, request, principal));
  }

  private <T> T route(
      EnvironmentId environmentId,
      Supplier<T> localCall,
      Function<ClusterOwnerRoute, T> remoteCall) {
    Objects.requireNonNull(environmentId, "environmentId");
    ClusterOwnerRoute owner =
        registry
            .route(environmentId)
            .orElseThrow(
                () ->
                    DomainException.unavailable(
                        "DAEMON_NOT_READY", "environment IDE owner route is unavailable"));
    return owner.local() ? localCall.get() : remoteCall.apply(owner);
  }

  private void routeAction(
      EnvironmentId environmentId, Runnable localCall, Consumer<ClusterOwnerRoute> remoteCall) {
    route(
        environmentId,
        () -> {
          localCall.run();
          return null;
        },
        owner -> {
          remoteCall.accept(owner);
          return null;
        });
  }

  private static String parseViewId(String viewId) {
    return IdeUuids.requireInput(viewId, "viewId").toString();
  }
}
