package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.EnvironmentIdeServiceImpl;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.web.environment.cluster.ClusterInternalRequests;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * 跨节点浏览器数据面的 owner HTTP 入口。
 *
 * <ul>
 *   <li>{@code /internal/environment-ide/{env}/data-http?viewId=...&path=...}：视图作用域数据流；
 *   <li>{@code /internal/environment-ide/{env}/http?path=...}：既有 bootstrap/session 管理路径。
 * </ul>
 *
 * <p>安全顺序固定为「认证 → 目标解析 → 本节点 owner 核对 → 数据面」：HMAC 覆盖 method 与完整内部 path/query，任何校验都在读取请求体之前 完成。本节点不是
 * READY owner 时返回 409，绝不递归转发、绝不自动重放。
 */
@Slf4j
@RestController
public final class IdeInternalDataPlaneController {

  private static final String BOOTSTRAP_PATH = "/" + EnvironmentIdeServiceImpl.BOOTSTRAP_PATH;

  private static final String SESSION_PATH = "/" + EnvironmentIdeServiceImpl.SESSION_PATH;

  private final ClusterInternalAuthentication authentication;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final IdeGatewayDataPlane dataPlane;

  public IdeInternalDataPlaneController(
      ClusterInternalAuthentication authentication,
      ClusterNodeRegistry clusterNodeRegistry,
      IdeGatewayDataPlane dataPlane) {
    this.authentication = authentication;
    this.clusterNodeRegistry = clusterNodeRegistry;
    this.dataPlane = dataPlane;
  }

  @RequestMapping(
      value =
          IdeInternalPaths.INTERNAL_IDE_PREFIX
              + "/{environmentId}"
              + IdeDataInternalPaths.DATA_HTTP_SUFFIX,
      method = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.PATCH,
        RequestMethod.DELETE,
        RequestMethod.HEAD
      })
  public void dataHttp(
      @PathVariable String environmentId, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    authentication.verify(
        request.getMethod(),
        ClusterInternalRequests.canonicalPath(request),
        ClusterInternalRequests.authHeaders(request));
    EnvironmentId environment = parseEnvironmentId(environmentId);
    requireLocalOwner(environment);
    UUID viewId = requireViewId(request);
    String rawPathAndQuery = requirePathParameter(request);
    IdeForwardedRequest forwarded = IdeForwardedRequest.of(request, rawPathAndQuery);
    String path = pathOf(rawPathAndQuery);
    if (BOOTSTRAP_PATH.equals(path)) {
      dataPlane.handleLocalBootstrap(environment, viewId, forwarded, response);
      return;
    }
    if (SESSION_PATH.equals(path)) {
      dataPlane.handleLocalSession(environment, viewId, forwarded, response);
      return;
    }
    dataPlane.handleInternalHttp(environment, viewId, rawPathAndQuery, forwarded, response);
  }

  @RequestMapping(
      value =
          IdeInternalPaths.INTERNAL_IDE_PREFIX + "/{environmentId}" + IdeInternalPaths.HTTP_SUFFIX,
      method = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.PATCH,
        RequestMethod.DELETE,
        RequestMethod.HEAD
      })
  public void legacyHttp(
      @PathVariable String environmentId, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    authentication.verify(
        request.getMethod(),
        ClusterInternalRequests.canonicalPath(request),
        ClusterInternalRequests.authHeaders(request));
    EnvironmentId environment = parseEnvironmentId(environmentId);
    requireLocalOwner(environment);
    String rawPathAndQuery = requirePathParameter(request);
    throw new AiValidationException("environment", "legacy internal IDE path is not supported");
  }

  private static String pathOf(String rawPathAndQuery) {
    int question = rawPathAndQuery.indexOf('?');
    return question < 0 ? rawPathAndQuery : rawPathAndQuery.substring(0, question);
  }

  /** 原始 path 必须存在；缺失即 fail-closed（认证通过也不放行）。 */
  private static String requirePathParameter(HttpServletRequest request) {
    String rawPathAndQuery = request.getParameter(IdeDataInternalPaths.PATH_PARAMETER);
    if (rawPathAndQuery == null || rawPathAndQuery.isEmpty() || !rawPathAndQuery.startsWith("/")) {
      throw new AiValidationException("environment", "internal IDE path is invalid");
    }
    return rawPathAndQuery;
  }

  private static UUID requireViewId(HttpServletRequest request) {
    String value = request.getParameter(IdeDataInternalPaths.VIEW_ID_PARAMETER);
    if (value == null) {
      throw new AiValidationException("environment", "internal IDE viewId is missing");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException("viewId is not canonical");
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("environment", "internal IDE viewId is invalid");
    }
  }

  private void requireLocalOwner(EnvironmentId environmentId) {
    boolean local =
        clusterNodeRegistry.route(environmentId).filter(ClusterOwnerRoute::local).isPresent();
    if (!local) {
      throw new AiValidationException(
          "environment", "internal IDE request does not match this node's READY owner route");
    }
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
    log.debug("rejected internal IDE data plane request: {}", error.getMessage());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(Map.of("message", "internal authentication failed"));
  }

  @ExceptionHandler(AiValidationException.class)
  public ResponseEntity<Map<String, String>> handleValidation(AiValidationException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("message", "internal IDE request is not accepted by this node"));
  }
}
