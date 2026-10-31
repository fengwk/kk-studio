package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.net.URI;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

/** 意图：浏览器数据握手只接受 canonical viewId，大写身份在握手阶段拒绝。 */
class IdeDataHandshakeInterceptorTest {

  @Test
  void rejectsUppercaseBrowserViewId() {
    EnvironmentId environment = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    ClusterInternalAuthentication authentication = mock(ClusterInternalAuthentication.class);
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    when(registry.route(environment))
        .thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));
    IdeDataHandshakeInterceptor interceptor =
        new IdeDataHandshakeInterceptor(authentication, registry, true);
    String viewId = "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE";
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getURI())
        .thenReturn(
            URI.create(
                IdeInternalPaths.INTERNAL_IDE_PREFIX
                    + "/"
                    + environment
                    + "/data?viewId="
                    + viewId
                    + "&path=%2F"));
    when(request.getHeaders()).thenReturn(new HttpHeaders());

    boolean accepted =
        interceptor.beforeHandshake(
            request, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>());

    assertFalse(accepted);
  }
}
