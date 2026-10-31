package fun.fengwk.kkstudio.web.environment.cluster;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.update.EnvironmentUpdateService;
import fun.fengwk.kkstudio.platform.error.AiDomainException;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRuntimeStatusDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateStartDTO;
import fun.fengwk.kkstudio.web.api.RequestParameters;

import java.util.Map;

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
      throw new AiEnvironmentUnavailableException(
          "internal runtime request does not match this node's READY owner route");
    }
    return environment;
  }

  @ExceptionHandler(ClusterInternalAuthenticationException.class)
  public ResponseEntity<Map<String, String>> handleAuthentication(
      ClusterInternalAuthenticationException error) {
    log.debug("rejected internal environment request: {}", error.getMessage());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(Map.of("code", "unauthorized", "message", "internal authentication failed"));
  }

  @ExceptionHandler(AiDomainException.class)
  public ResponseEntity<Map<String, String>> handleDomain(AiDomainException error) {
    HttpStatus status = status(error);
    return ResponseEntity.status(status)
        .body(Map.of("code", error.code().code(), "message", message(error)));
  }

  private static HttpStatus status(AiDomainException error) {
    if (error instanceof AiValidationException) {
      return HttpStatus.BAD_REQUEST;
    }
    if (error instanceof AiResourceNotFoundException) {
      return HttpStatus.NOT_FOUND;
    }
    if (error instanceof AiInUseException || error instanceof AiEnvironmentUnavailableException) {
      return HttpStatus.CONFLICT;
    }
    return HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private static String message(AiDomainException error) {
    String text = error.getMessage();
    return text == null || text.isBlank() ? "internal environment request failed" : text;
  }
}
