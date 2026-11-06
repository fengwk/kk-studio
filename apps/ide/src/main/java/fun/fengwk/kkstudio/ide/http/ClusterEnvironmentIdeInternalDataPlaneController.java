package fun.fengwk.kkstudio.ide.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;
import fun.fengwk.kkstudio.ide.gateway.IdeForwardedRequest;
import fun.fengwk.kkstudio.ide.gateway.IdeGatewayDataPlane;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.cluster.ClusterInternalRequests;
import fun.fengwk.kkstudio.platform.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;
import fun.fengwk.kkstudio.platform.http.RequestParameters;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * 跨节点 IDE 数据面的内部 REST / WebSocket 入口。
 *
 * <p>单跳转发的 HTTP 请求与 WebSocket upgrade 均在此完成 HMAC 验证与 owner 核对后， 由 {@link IdeGatewayDataPlane} 交付给本地
 * Daemon 隧道。
 */
@RestController
@RequestMapping(IdeInternalPaths.INTERNAL_IDE_PREFIX + "/{environmentId}")
public class ClusterEnvironmentIdeInternalDataPlaneController {

  private final IdeGatewayDataPlane dataPlane;
  private final ClusterNodeRegistry clusterNodeRegistry;
  private final ClusterInternalAuthentication authentication;

  public ClusterEnvironmentIdeInternalDataPlaneController(
      IdeGatewayDataPlane dataPlane,
      ClusterNodeRegistry clusterNodeRegistry,
      ClusterInternalAuthentication authentication) {
    this.dataPlane = Objects.requireNonNull(dataPlane, "dataPlane");
    this.clusterNodeRegistry = Objects.requireNonNull(clusterNodeRegistry, "clusterNodeRegistry");
    this.authentication = Objects.requireNonNull(authentication, "authentication");
  }

  @RequestMapping("/data-http")
  public void dataHttp(
      @PathVariable String environmentId,
      @RequestParam("viewId") String viewIdStr,
      @RequestParam("path") String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    EnvironmentId envId = authenticateAndAuthorize(request, environmentId);
    UUID viewId = Uuids.parseCanonical(viewIdStr, "viewId");
    HttpServletRequest forwarded = IdeForwardedRequest.of(request, rawPathAndQuery);
    dataPlane.handleInternalDataPlane(envId, viewId, rawPathAndQuery, forwarded, response);
  }

  @GetMapping("/data-ws")
  public void dataWebSocket(
      @PathVariable String environmentId,
      @RequestParam("viewId") String viewIdStr,
      @RequestParam("path") String rawPathAndQuery,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    EnvironmentId envId = authenticateAndAuthorize(request, environmentId);
    UUID viewId = Uuids.parseCanonical(viewIdStr, "viewId");
    HttpServletRequest forwarded = IdeForwardedRequest.of(request, rawPathAndQuery);
    dataPlane.handleInternalDataPlane(envId, viewId, rawPathAndQuery, forwarded, response);
  }

  /** 认证与 owner 核对先于任何转发；失败不会进入数据面。 */
  private EnvironmentId authenticateAndAuthorize(HttpServletRequest request, String environmentId) {
    authentication.verify(
        request.getMethod(),
        ClusterInternalRequests.canonicalPath(request),
        ClusterInternalRequests.authHeaders(request));
    EnvironmentId envId = RequestParameters.environmentId(environmentId);
    boolean local = clusterNodeRegistry.route(envId).filter(ClusterOwnerRoute::local).isPresent();
    if (!local) {
      throw DomainException.unavailable(
          "IDE_UNAVAILABLE",
          "internal IDE data request does not match this node's READY owner route");
    }
    return envId;
  }
}
