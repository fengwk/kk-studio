package fun.fengwk.kkstudio.web.environment.cluster;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterIdeOwnerRoute;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.platform.error.AiDomainException;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentIdeUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨节点 IDE 元数据命令的内部 REST 入口。
 *
 * <p>这些 endpoint 只接受已认证的其他节点请求：先在任何 Daemon 副作用之前校验 HMAC 与目标实例，再核对本节点确实持有该 Environment 的 READY owner
 * 路由，最后直接调用本地 {@link EnvironmentIdeServiceImpl}（显式 qualifier），绝不递归回组合实现。回执只有 ViewDTO，不含 任何完整 cookie
 * 或注册 token。
 *
 * <p>反向数据面（{@code /http}、{@code /ws}）由 gateway 宿主实现并复用同一 {@link ClusterInternalAuthentication}；本
 * controller 只处理 view 元数据命令。
 */
@Slf4j
@RestController
@RequestMapping(ClusterInternalPaths.INTERNAL_IDE_PREFIX + "/{environmentId}")
public class ClusterEnvironmentIdeInternalController {

  private static final List<String> AUTH_HEADER_NAMES =
      List.of(
          HmacClusterInternalAuthentication.HEADER_TARGET,
          HmacClusterInternalAuthentication.HEADER_TIMESTAMP,
          HmacClusterInternalAuthentication.HEADER_NONCE,
          HmacClusterInternalAuthentication.HEADER_SIGNATURE);

  private final EnvironmentIdeServiceImpl localEnvironmentIdeService;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final ClusterInternalAuthentication authentication;

  public ClusterEnvironmentIdeInternalController(
      @Qualifier("localEnvironmentIdeService") EnvironmentIdeServiceImpl localEnvironmentIdeService,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalAuthentication authentication) {
    this.localEnvironmentIdeService = localEnvironmentIdeService;
    this.clusterNodeRegistry = clusterNodeRegistry;
    this.authentication = authentication;
  }

  @PostMapping("/views")
  public EnvironmentIdeViewDTO openView(
      @PathVariable String environmentId,
      @RequestBody EnvironmentIdeOpenViewDTO request,
      HttpServletRequest httpRequest) {
    EnvironmentId environment = authenticate(httpRequest, environmentId);
    requireLocalOwner(environment);
    return localEnvironmentIdeService.openView(environment, request);
  }

  @PutMapping("/{viewId}/directory")
  public EnvironmentIdeViewDTO changeDirectory(
      @PathVariable String environmentId,
      @PathVariable String viewId,
      @RequestBody EnvironmentIdeChangeDirectoryDTO request,
      HttpServletRequest httpRequest) {
    EnvironmentId environment = authenticate(httpRequest, environmentId);
    requireLocalOwner(environment);
    return localEnvironmentIdeService.changeDirectory(environment, viewId, request);
  }

  @PostMapping("/{viewId}/terminal")
  public void focusTerminal(
      @PathVariable String environmentId,
      @PathVariable String viewId,
      HttpServletRequest httpRequest) {
    EnvironmentId environment = authenticate(httpRequest, environmentId);
    requireLocalOwner(environment);
    localEnvironmentIdeService.focusTerminal(environment, viewId);
  }

  @DeleteMapping("/{viewId}")
  public void closeView(
      @PathVariable String environmentId,
      @PathVariable String viewId,
      HttpServletRequest httpRequest) {
    EnvironmentId environment = authenticate(httpRequest, environmentId);
    requireLocalOwner(environment);
    localEnvironmentIdeService.closeView(environment, viewId);
  }

  private EnvironmentId authenticate(HttpServletRequest request, String environmentId) {
    String requestPath = canonicalPath(request);
    authentication.verify(request.getMethod(), requestPath, authHeaders(request));
    return parseEnvironmentId(environmentId);
  }

  private void requireLocalOwner(EnvironmentId environmentId) {
    boolean local =
        clusterNodeRegistry.route(environmentId).filter(ClusterIdeOwnerRoute::local).isPresent();
    if (!local) {
      throw new AiEnvironmentIdeUnavailableException(
          "internal IDE request does not match this node's READY owner route");
    }
  }

  private static String canonicalPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String query = request.getQueryString();
    return query == null ? uri : uri + "?" + query;
  }

  private static Map<String, String> authHeaders(HttpServletRequest request) {
    Map<String, String> headers = new HashMap<>();
    for (String name : AUTH_HEADER_NAMES) {
      String value = request.getHeader(name);
      if (value != null) {
        headers.put(name, value);
      }
    }
    return headers;
  }

  private static EnvironmentId parseEnvironmentId(String text) {
    try {
      return EnvironmentId.parse(text);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("environmentId", "environmentId must be a canonical UUID");
    }
  }

  @ExceptionHandler(ClusterInternalAuthenticationException.class)
  public ResponseEntity<Map<String, String>> handleAuthentication(
      ClusterInternalAuthenticationException error) {
    log.debug("rejected internal IDE request: {}", error.getMessage());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(Map.of("message", "internal authentication failed"));
  }

  @ExceptionHandler(AiDomainException.class)
  public ResponseEntity<Map<String, String>> handleDomain(AiDomainException error) {
    HttpStatus status =
        error instanceof AiValidationException
            ? HttpStatus.BAD_REQUEST
            : error instanceof AiEnvironmentIdeUnavailableException
                ? HttpStatus.CONFLICT
                : HttpStatus.INTERNAL_SERVER_ERROR;
    return ResponseEntity.status(status).body(Map.of("message", message(error)));
  }

  private static String message(AiDomainException error) {
    String text = error.getMessage();
    return text == null || text.isBlank() ? "internal IDE request failed" : text;
  }
}
