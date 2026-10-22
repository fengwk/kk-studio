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
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.web.environment.cluster.ClusterInternalRequests;

import java.io.IOException;
import java.util.Map;

/**
 * 跨节点浏览器 HTTP 数据面的 owner 入口：{@code /internal/environment-ide/{env}/http?path=<原始path/query>}。
 *
 * <p>安全顺序固定为「认证 → 目标解析 → 本节点 owner 核对 → 还原原始 path → 数据面」：HMAC 覆盖 method 与完整内部 path/query，任何校验都在读取
 * 请求体之前完成，因此未认证的大请求体不会被解析、也不会触发任何 Daemon 副作用。本节点不是 READY owner（陈旧/已接管）时以 409 未受理返回， 绝不递归转发、绝不自动重放。
 *
 * <p>请求体与响应体都由数据面流式搬运（bootstrap 跳转只回 303 与 Set-Cookie），不整包缓冲、不落 SQL/PG。
 */
@Slf4j
@RestController
public final class IdeInternalDataPlaneController {

  private static final String BOOTSTRAP_PATH = "/__ide/bootstrap";

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
          IdeInternalPaths.INTERNAL_IDE_PREFIX + "/{environmentId}" + IdeInternalPaths.HTTP_SUFFIX,
      method = {
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.PUT,
        RequestMethod.PATCH,
        RequestMethod.DELETE,
        RequestMethod.HEAD
      })
  public void http(
      @PathVariable String environmentId, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    authentication.verify(
        request.getMethod(),
        ClusterInternalRequests.canonicalPath(request),
        ClusterInternalRequests.authHeaders(request));
    EnvironmentId environment = parseEnvironmentId(environmentId);
    requireLocalOwner(environment);
    String rawPathAndQuery = requirePathParameter(request);
    IdeForwardedRequest forwarded = IdeForwardedRequest.of(request, rawPathAndQuery);
    if (isBootstrapPath(rawPathAndQuery)) {
      // bootstrap 消费（一次性票据 + Set-Cookie + 跳转）同样只在 owner 执行。
      dataPlane.handleBootstrap(environment, forwarded, response);
      return;
    }
    dataPlane.handleInternalHttp(environment, rawPathAndQuery, forwarded, response);
  }

  private boolean isBootstrapPath(String rawPathAndQuery) {
    int question = rawPathAndQuery.indexOf('?');
    String path = question < 0 ? rawPathAndQuery : rawPathAndQuery.substring(0, question);
    return BOOTSTRAP_PATH.equals(path);
  }

  /** 原始 path 必须存在；缺失即 fail-closed（认证通过也不放行）。 */
  private static String requirePathParameter(HttpServletRequest request) {
    String rawPathAndQuery = request.getParameter(IdeInternalPaths.PATH_PARAMETER);
    if (rawPathAndQuery == null || rawPathAndQuery.isEmpty() || !rawPathAndQuery.startsWith("/")) {
      throw new AiValidationException("environment", "internal IDE path is invalid");
    }
    return rawPathAndQuery;
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
