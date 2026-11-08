package fun.fengwk.kkstudio.web.environment;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentRuntimeStatusDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateStartDTO;
import fun.fengwk.kkstudio.platform.environment.update.EnvironmentUpdateService;
import fun.fengwk.kkstudio.web.environment.cluster.EnvironmentRuntimeOwnerClient;

/**
 * Environment 运行状态与受管更新的跨节点编排：按当前 READY owner 路由在本地读取/执行，或经内部 HMAC 单跳到 owner。
 *
 * <p>只在 READY owner 上执行一次严格准入，不向其它节点扇出；没有路由即明确 unavailable，不伪造运行快照。
 */
@Service
@AllArgsConstructor
public class EnvironmentRuntimeRoutingService {

  private final EnvironmentUpdateService environmentUpdateService;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final EnvironmentRuntimeOwnerClient runtimeOwnerClient;

  /** 读取 owner 的运行快照：允许 CONNECTING 的有效 owner 租约。 */
  public EnvironmentRuntimeStatusDTO runtimeStatus(EnvironmentId environmentId) {
    ClusterOwnerRoute route = requireRuntimeOwnerRoute(environmentId);
    if (route.local()) {
      return environmentUpdateService.runtimeStatus(environmentId);
    }
    return runtimeOwnerClient.runtimeStatus(route, environmentId);
  }

  /** 在 owner 上发起一次受管更新。 */
  public EnvironmentUpdateStartDTO startUpdate(EnvironmentId environmentId) {
    ClusterOwnerRoute route = requireOwnerRoute(environmentId);
    if (route.local()) {
      return environmentUpdateService.startUpdate(environmentId);
    }
    return runtimeOwnerClient.startUpdate(route, environmentId);
  }

  private ClusterOwnerRoute requireRuntimeOwnerRoute(EnvironmentId environmentId) {
    return clusterNodeRegistry
        .runtimeRoute(environmentId)
        .orElseThrow(
            () ->
                DomainException.unavailable(
                    "ENVIRONMENT_UNAVAILABLE",
                    "environment has no current owner route"));
  }

  private ClusterOwnerRoute requireOwnerRoute(EnvironmentId environmentId) {
    return clusterNodeRegistry
        .route(environmentId)
        .orElseThrow(
            () ->
                DomainException.unavailable(
                    "ENVIRONMENT_UNAVAILABLE", "environment has no current READY owner route"));
  }
}
