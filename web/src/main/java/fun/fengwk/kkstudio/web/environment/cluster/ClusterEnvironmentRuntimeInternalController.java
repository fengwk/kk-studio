package fun.fengwk.kkstudio.web.environment.cluster;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalErrors;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalRequests;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.update.EnvironmentUpdateService;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.http.RequestParameters;
import fun.fengwk.kkstudio.share.environment.EnvironmentRuntimeStatusDTO;
import fun.fengwk.kkstudio.share.environment.EnvironmentUpdateStartDTO;

/**
 * 跨节点 Environment runtime/update 的内部 REST 入口。
 *
 * <p>顺序固定为 HMAC 认证 → 目标本节点 READY owner 核对 → 调用本地更新服务。它只做单跳转发，不向所有节点广播，也不递归回组合实现。
 */
@Slf4j
@RestController
@RequestMapping(ClusterInternalPaths.INTERNAL_ENVIRONMENT_PREFIX + "/{environmentId}")
public class ClusterEnvironmentRuntimeInternalController {

  private final EnvironmentUpdateService localEnvironmentUpdateService;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final ClusterInternalAuthentication authentication;

  public ClusterEnvironmentRuntimeInternalController(
      EnvironmentUpdateService localEnvironmentUpdateService,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalAuthentication authentication) {
    this.localEnvironmentUpdateService = localEnvironmentUpdateService;
    this.clusterNodeRegistry = clusterNodeRegistry;
    this.authentication = authentication;
  }

  @GetMapping("/runtime")
  public EnvironmentRuntimeStatusDTO runtimeStatus(
      @PathVariable String environmentId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    return localEnvironmentUpdateService.runtimeStatus(environment);
  }

  @PostMapping("/update")
  public EnvironmentUpdateStartDTO startUpdate(
      @PathVariable String environmentId, HttpServletRequest request) {
    EnvironmentId environment = authenticateAndAuthorize(request, environmentId);
    return localEnvironmentUpdateService.startUpdate(environment);
  }

  private EnvironmentId authenticateAndAuthorize(HttpServletRequest request, String environmentId) {
    authentication.verify(
        request.getMethod(),
        ClusterInternalRequests.canonicalPath(request),
        ClusterInternalRequests.authHeaders(request));
    EnvironmentId environment = RequestParameters.environmentId(environmentId);
    boolean local =
        clusterNodeRegistry.route(environment).filter(ClusterOwnerRoute::local).isPresent();
    if (!local) {
      throw DomainException.unavailable(
          "ENVIRONMENT_UNAVAILABLE",
          "internal runtime request does not match this node's READY owner route");
    }
    return environment;
  }

  @ExceptionHandler(ClusterInternalAuthenticationException.class)
  public ResponseEntity<ApiResponse<Void>> handleAuthentication(
      ClusterInternalAuthenticationException error) {
    log.debug("rejected internal environment request: {}", error.getMessage());
    return ClusterInternalErrors.unauthorized();
  }

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ApiResponse<Void>> handleDomain(DomainException error) {
    return ClusterInternalErrors.domain(error);
  }
}
