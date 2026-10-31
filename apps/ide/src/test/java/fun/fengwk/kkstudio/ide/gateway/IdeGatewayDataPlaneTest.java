package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.harness.environment.tunnel.EnvironmentTunnels;
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.harness.environment.tunnel.testing.FakeEnvironmentTunnel;
import fun.fengwk.kkstudio.harness.environment.tunnel.testing.FakeTunnelExchange;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

class IdeGatewayDataPlaneTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, UUID.randomUUID(), 1L);

  @Test
  void rejectsCrossSiteOrUnsafePath() throws IOException {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    EnvironmentTunnels tunnels = mock(EnvironmentTunnels.class);
    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(
            registry,
            tunnels,
            authorizer,
            sessionHandler,
            forwarder,
            Executors.newSingleThreadExecutor());

    HttpServletRequest crossSiteReq = mock(HttpServletRequest.class);
    when(crossSiteReq.getMethod()).thenReturn("GET");
    when(crossSiteReq.getHeader("Sec-Fetch-Site")).thenReturn("cross-site");
    HttpServletResponse crossSiteRes = mock(HttpServletResponse.class);

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/src/main.ts", crossSiteReq, crossSiteRes);
    verify(crossSiteRes).sendError(HttpServletResponse.SC_FORBIDDEN);

    HttpServletRequest unsafeReq = mock(HttpServletRequest.class);
    when(unsafeReq.getMethod()).thenReturn("GET");
    when(unsafeReq.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    HttpServletResponse unsafeRes = mock(HttpServletResponse.class);

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/../escape", unsafeReq, unsafeRes);
    verify(unsafeRes).sendError(HttpServletResponse.SC_BAD_REQUEST);
  }

  @Test
  void forwardsRemoteTraffic() throws IOException {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    ClusterOwnerRoute remoteRoute =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("http://remote.internal"));
    when(registry.route(ENV)).thenReturn(Optional.of(remoteRoute));

    EnvironmentTunnels tunnels = mock(EnvironmentTunnels.class);
    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(
            registry,
            tunnels,
            authorizer,
            sessionHandler,
            forwarder,
            Executors.newSingleThreadExecutor());

    HttpServletRequest httpReq = mock(HttpServletRequest.class);
    when(httpReq.getMethod()).thenReturn("GET");
    when(httpReq.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    HttpServletResponse httpRes = mock(HttpServletResponse.class);

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/src/main.ts", httpReq, httpRes);
    verify(forwarder)
        .forwardHttp(
            eq(httpReq), eq(httpRes), eq(remoteRoute), eq(ENV), eq(VIEW), eq("/src/main.ts"));

    HttpServletRequest wsReq = mock(HttpServletRequest.class);
    when(wsReq.getMethod()).thenReturn("GET");
    when(wsReq.getHeader("Upgrade")).thenReturn("websocket");
    when(wsReq.getHeader("Connection")).thenReturn("Upgrade");
    when(wsReq.getHeader("Origin")).thenReturn("http://localhost:8080");
    when(wsReq.getScheme()).thenReturn("http");
    when(wsReq.getServerName()).thenReturn("localhost");
    when(wsReq.getServerPort()).thenReturn(8080);
    HttpServletResponse wsRes = mock(HttpServletResponse.class);

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/ws", wsReq, wsRes);
    verify(forwarder)
        .forwardWebSocket(eq(wsReq), eq(wsRes), eq(remoteRoute), eq(ENV), eq(VIEW), eq("/ws"));
  }

  @Test
  void localHttpDispatchSuccess() throws Exception {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    ClusterOwnerRoute localRoute = ClusterOwnerRoute.local(UUID.randomUUID());
    when(registry.route(ENV)).thenReturn(Optional.of(localRoute));

    FakeEnvironmentTunnel tunnel = new FakeEnvironmentTunnel(IDENTITY);
    EnvironmentTunnels tunnels = mock(EnvironmentTunnels.class);
    when(tunnels.local(ENV)).thenReturn(Optional.of(tunnel));

    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    when(authorizer.authorize(eq(ENV), eq(VIEW), any()))
        .thenReturn(
            Optional.of(
                new IdeDataViewAuthorizer.IdeDataViewAccess(
                    IDENTITY, VIEW, 1L, Set.of("/workspace"))));

    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(
            registry,
            tunnels,
            authorizer,
            sessionHandler,
            forwarder,
            Executors.newSingleThreadExecutor());

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getContextPath()).thenReturn("");
    when(req.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    when(req.getHeader("Cookie")).thenReturn("kk_ide_session=token");
    when(req.getParameter("workspace")).thenReturn("/workspace");
    when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    when(req.getContentLengthLong()).thenReturn(4L);

    ByteArrayInputStream in = new ByteArrayInputStream("body".getBytes(StandardCharsets.UTF_8));
    ServletInputStream servletIn =
        new ServletInputStream() {
          @Override
          public boolean isFinished() {
            return in.available() == 0;
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setReadListener(ReadListener readListener) {}

          @Override
          public int read() {
            return in.read();
          }
        };
    when(req.getInputStream()).thenReturn(servletIn);

    HttpServletResponse res = mock(HttpServletResponse.class);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ServletOutputStream servletOut =
        new ServletOutputStream() {
          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setWriteListener(WriteListener writeListener) {}

          @Override
          public void write(int b) {
            out.write(b);
          }
        };
    when(res.getOutputStream()).thenReturn(servletOut);

    // Queue response in fake tunnel
    tunnel.onOpenHttp(
        tunnelReq -> {
          FakeTunnelExchange exchange = new FakeTunnelExchange(tunnelReq);
          exchange.completeResponseHeaders(
              new TunnelExchange.Response(200, Map.of("Content-Type", List.of("text/plain"))));
          exchange
              .fakeStream()
              .pushReadChunk(ByteBuffer.wrap("daemon-response".getBytes(StandardCharsets.UTF_8)));
          exchange.fakeStream().pushEof();
          return exchange;
        });

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/src/app.ts", req, res);

    verify(res).setStatus(200);
    verify(res).addHeader("Content-Type", "text/plain");
    assertEquals("daemon-response", out.toString(StandardCharsets.UTF_8));
  }
}
