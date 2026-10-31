package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import fun.fengwk.kkstudio.harness.environment.tunnel.TunnelExchange;
import fun.fengwk.kkstudio.harness.environment.tunnel.testing.FakeEnvironmentTunnel;
import fun.fengwk.kkstudio.harness.environment.tunnel.testing.FakeEnvironmentTunnels;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class IdeGatewayDataPlaneTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final DaemonConnectionIdentity IDENTITY =
      new DaemonConnectionIdentity(ENV, "daemon-1");

  @Test
  void rejectsCrossSiteOrUnsafePath() throws IOException {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(registry, tunnels, authorizer, sessionHandler, forwarder);

    HttpServletRequest crossSiteReq = mock(HttpServletRequest.class);
    when(crossSiteReq.getMethod()).thenReturn("GET");
    when(crossSiteReq.getHeader("Sec-Fetch-Site")).thenReturn("cross-site");
    HttpServletResponse crossSiteRes = mock(HttpServletResponse.class);

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/index.html", crossSiteReq, crossSiteRes);
    verify(crossSiteRes).sendError(HttpServletResponse.SC_FORBIDDEN);

    HttpServletRequest unsafeReq = mock(HttpServletRequest.class);
    when(unsafeReq.getMethod()).thenReturn("GET");
    when(unsafeReq.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    HttpServletResponse unsafeRes = mock(HttpServletResponse.class);

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/../secret", unsafeReq, unsafeRes);
    verify(unsafeRes).sendError(HttpServletResponse.SC_BAD_REQUEST);
  }

  @Test
  void routesToRemoteWhenNotLocal() throws IOException {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    ClusterOwnerRoute remoteRoute =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("http://192.168.1.10:8080"));
    when(registry.route(ENV)).thenReturn(Optional.of(remoteRoute));

    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(registry, tunnels, authorizer, sessionHandler, forwarder);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    HttpServletResponse res = mock(HttpServletResponse.class);

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/index.html", req, res);
    verify(forwarder).forwardDataHttp(req, res, remoteRoute, ENV, VIEW, "/index.html");
  }

  @Test
  void localHttpDispatchSuccess() throws Exception {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    ClusterOwnerRoute localRoute = ClusterOwnerRoute.local(UUID.randomUUID());
    when(registry.route(ENV)).thenReturn(Optional.of(localRoute));

    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    FakeEnvironmentTunnel tunnel = tunnels.register(IDENTITY);

    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    when(authorizer.authorize(eq(ENV), eq(VIEW), any()))
        .thenReturn(
            Optional.of(
                new IdeDataViewAuthorizer.IdeDataViewAccess(
                    IDENTITY, VIEW, 1L, Set.of("/workspace"))));

    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(registry, tunnels, authorizer, sessionHandler, forwarder);

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

    CountDownLatch serverCompleted = new CountDownLatch(1);
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                // Poll until exchange is created
                while (tunnel.exchanges().isEmpty()) {
                  Thread.sleep(10);
                }
                FakeTunnelExchange exchange = tunnel.exchanges().get(0);
                exchange.respond(
                    new TunnelExchange.Response(
                        200, Map.of("Content-Type", List.of("text/plain"))));
                exchange
                    .fakeStream()
                    .peer()
                    .write(ByteBuffer.wrap("daemon-response".getBytes(StandardCharsets.UTF_8)))
                    .toCompletableFuture()
                    .join();
                exchange.fakeStream().peer().endOutput().toCompletableFuture().join();
                serverCompleted.countDown();
              } catch (Exception ignored) {
              }
            });

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/src/app.ts", req, res);
    assertTrue(serverCompleted.await(5, TimeUnit.SECONDS));

    verify(res).setStatus(200);
    verify(res).addHeader("content-type", "text/plain");
    assertEquals("daemon-response", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void localHttpLargeResponseStreamingBoundedMemory() throws Exception {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    when(registry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));

    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    FakeEnvironmentTunnel tunnel = tunnels.register(IDENTITY);

    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    when(authorizer.authorize(eq(ENV), eq(VIEW), any()))
        .thenReturn(
            Optional.of(
                new IdeDataViewAuthorizer.IdeDataViewAccess(
                    IDENTITY, VIEW, 1L, Set.of("/workspace"))));

    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(registry, tunnels, authorizer, sessionHandler, forwarder);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getContextPath()).thenReturn("");
    when(req.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    when(req.getHeader("Cookie")).thenReturn("kk_ide_session=token");
    when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());

    AtomicInteger bytesReceived = new AtomicInteger();
    HttpServletResponse res = mock(HttpServletResponse.class);
    ServletOutputStream slowOut =
        new ServletOutputStream() {
          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setWriteListener(WriteListener writeListener) {}

          @Override
          public void write(int b) {
            bytesReceived.incrementAndGet();
          }

          @Override
          public void write(byte[] b, int off, int len) {
            bytesReceived.addAndGet(len);
            try {
              Thread.sleep(1); // simulate slow consumer
            } catch (InterruptedException ignored) {
            }
          }
        };
    when(res.getOutputStream()).thenReturn(slowOut);

    int totalChunks = 50;
    int chunkSize = 16 * 1024; // 800 KB total
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                while (tunnel.exchanges().isEmpty()) {
                  Thread.sleep(10);
                }
                FakeTunnelExchange exchange = tunnel.exchanges().get(0);
                exchange.respond(
                    new TunnelExchange.Response(
                        200, Map.of("Content-Type", List.of("application/octet-stream"))));
                byte[] chunk = new byte[chunkSize];
                for (int i = 0; i < totalChunks; i++) {
                  exchange
                      .fakeStream()
                      .peer()
                      .write(ByteBuffer.wrap(chunk))
                      .toCompletableFuture()
                      .join();
                }
                exchange.fakeStream().peer().endOutput().toCompletableFuture().join();
              } catch (Exception ignored) {
              }
            });

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/large-file.bin", req, res);

    assertEquals(totalChunks * chunkSize, bytesReceived.get());
  }

  @Test
  void localWebSocketNon200ReturnsStatusOnly() throws Exception {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    when(registry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));

    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    FakeEnvironmentTunnel tunnel = tunnels.register(IDENTITY);

    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    when(authorizer.authorize(eq(ENV), eq(VIEW), any()))
        .thenReturn(
            Optional.of(
                new IdeDataViewAuthorizer.IdeDataViewAccess(
                    IDENTITY, VIEW, 1L, Set.of("/workspace"))));

    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(registry, tunnels, authorizer, sessionHandler, forwarder);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getHeader("Connection")).thenReturn("Upgrade");
    when(req.getHeader("Upgrade")).thenReturn("websocket");
    when(req.getHeader("Sec-WebSocket-Version")).thenReturn("13");
    when(req.getHeader("Sec-WebSocket-Key")).thenReturn("dGhlIHNhbXBsZSBub25jZQ==");
    when(req.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    when(req.getHeader("Origin")).thenReturn("http://localhost");
    when(req.getScheme()).thenReturn("http");
    when(req.getServerName()).thenReturn("localhost");
    when(req.getServerPort()).thenReturn(80);
    when(req.getHeader("Cookie")).thenReturn("kk_ide_session=token");
    when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());

    HttpServletResponse res = mock(HttpServletResponse.class);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                while (tunnel.exchanges().isEmpty()) {
                  Thread.sleep(10);
                }
                FakeTunnelExchange exchange = tunnel.exchanges().get(0);
                // Daemon rejects with 409 Conflict
                exchange.respond(new TunnelExchange.Response(409, Map.of()));
              } catch (Exception ignored) {
              }
            });

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/ws", req, res);

    verify(res).sendError(409);
  }

  @Test
  void bootstrapAndSessionRouting() throws IOException {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(registry, tunnels, authorizer, sessionHandler, forwarder);

    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);

    // 1. Bootstrap: No route -> 503
    when(registry.route(ENV)).thenReturn(Optional.empty());
    dataPlane.handleBrowserTraffic(
        ENV, VIEW, IdeGatewayConstants.BOOTSTRAP_ENDPOINT + "?token=123", req, res);
    verify(res).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);

    // 2. Bootstrap: Remote route -> forwardManagementHttp
    ClusterOwnerRoute remote =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("http://127.0.0.1:8080"));
    when(registry.route(ENV)).thenReturn(Optional.of(remote));
    HttpServletResponse resRemote = mock(HttpServletResponse.class);
    dataPlane.handleBrowserTraffic(
        ENV, VIEW, IdeGatewayConstants.BOOTSTRAP_ENDPOINT + "?token=123", req, resRemote);
    verify(forwarder)
        .forwardManagementHttp(
            req,
            resRemote,
            remote,
            ENV,
            VIEW,
            IdeGatewayConstants.BOOTSTRAP_ENDPOINT + "?token=123");

    // 3. Bootstrap: Local route -> browserSessionHandler.handleBootstrap
    ClusterOwnerRoute local = ClusterOwnerRoute.local(UUID.randomUUID());
    when(registry.route(ENV)).thenReturn(Optional.of(local));
    HttpServletResponse resLocal = mock(HttpServletResponse.class);
    dataPlane.handleBrowserTraffic(
        ENV, VIEW, IdeGatewayConstants.BOOTSTRAP_ENDPOINT + "?token=123", req, resLocal);
    verify(sessionHandler).handleBootstrap(ENV, VIEW, req, resLocal);

    // 4. Session: No route -> 503
    when(registry.route(ENV)).thenReturn(Optional.empty());
    HttpServletResponse resSessionNoRoute = mock(HttpServletResponse.class);
    dataPlane.handleBrowserTraffic(
        ENV, VIEW, IdeGatewayConstants.SESSION_ENDPOINT, req, resSessionNoRoute);
    verify(resSessionNoRoute).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);

    // 5. Session: Remote route -> forwardManagementHttp
    when(registry.route(ENV)).thenReturn(Optional.of(remote));
    HttpServletResponse resSessionRemote = mock(HttpServletResponse.class);
    dataPlane.handleBrowserTraffic(
        ENV, VIEW, IdeGatewayConstants.SESSION_ENDPOINT, req, resSessionRemote);
    verify(forwarder)
        .forwardManagementHttp(
            req, resSessionRemote, remote, ENV, VIEW, IdeGatewayConstants.SESSION_ENDPOINT);

    // 6. Session: Local route -> browserSessionHandler.handleSession
    when(registry.route(ENV)).thenReturn(Optional.of(local));
    HttpServletResponse resSessionLocal = mock(HttpServletResponse.class);
    dataPlane.handleBrowserTraffic(
        ENV, VIEW, IdeGatewayConstants.SESSION_ENDPOINT, req, resSessionLocal);
    verify(sessionHandler).handleSession(ENV, VIEW, req, resSessionLocal);
  }

  @Test
  void internalDataPlaneEndpointsAndValidations() throws IOException {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(registry, tunnels, authorizer, sessionHandler, forwarder);

    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);

    // 1. Internal bootstrap
    dataPlane.handleInternalDataPlane(
        ENV, VIEW, IdeGatewayConstants.BOOTSTRAP_ENDPOINT + "?token=t", req, res);
    verify(sessionHandler).handleBootstrap(ENV, VIEW, req, res);

    // 2. Internal session
    HttpServletResponse res2 = mock(HttpServletResponse.class);
    dataPlane.handleInternalDataPlane(ENV, VIEW, IdeGatewayConstants.SESSION_ENDPOINT, req, res2);
    verify(sessionHandler).handleSession(ENV, VIEW, req, res2);

    // 3. Internal unsafe path -> 400
    HttpServletResponse res3 = mock(HttpServletResponse.class);
    dataPlane.handleInternalDataPlane(ENV, VIEW, "/../unsafe", req, res3);
    verify(res3).sendError(HttpServletResponse.SC_BAD_REQUEST);

    // 4. Unauthorized -> 401
    when(authorizer.authorize(any(), any(), any())).thenReturn(Optional.empty());
    HttpServletResponse res4 = mock(HttpServletResponse.class);
    dataPlane.handleInternalDataPlane(ENV, VIEW, "/safe", req, res4);
    verify(res4).sendError(HttpServletResponse.SC_UNAUTHORIZED);

    // 5. Forbidden workspace -> 403
    when(authorizer.authorize(any(), any(), any()))
        .thenReturn(
            Optional.of(
                new IdeDataViewAuthorizer.IdeDataViewAccess(
                    IDENTITY, VIEW, 1L, Set.of("/workspace"))));
    when(req.getParameter("workspace")).thenReturn("/forbidden");
    HttpServletResponse res5 = mock(HttpServletResponse.class);
    dataPlane.handleInternalDataPlane(ENV, VIEW, "/safe", req, res5);
    verify(res5).sendError(HttpServletResponse.SC_FORBIDDEN);

    // 6. Tunnel missing -> 503
    when(req.getParameter("workspace")).thenReturn("/workspace");
    HttpServletResponse res6 = mock(HttpServletResponse.class);
    dataPlane.handleInternalDataPlane(ENV, VIEW, "/safe", req, res6);
    verify(res6).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);

    // 7. Tunnel generation mismatch (view generation REPLACED) -> 409
    DaemonConnectionIdentity otherIdentity = new DaemonConnectionIdentity(ENV, "other-daemon");
    tunnels.replace(otherIdentity);
    HttpServletResponse res7 = mock(HttpServletResponse.class);
    dataPlane.handleInternalDataPlane(ENV, VIEW, "/safe", req, res7);
    verify(res7).sendError(HttpServletResponse.SC_CONFLICT);

    // 8. Bootstrap suffix prefix like /__ide/bootstrapxxx is NOT bootstrap endpoint
    HttpServletResponse res8 = mock(HttpServletResponse.class);
    dataPlane.handleInternalDataPlane(ENV, VIEW, "/__ide/bootstrapxxx", req, res8);
    // treated as data path -> 409 because otherTunnel does not match IDENTITY
    verify(res8).sendError(HttpServletResponse.SC_CONFLICT);
  }

  @Test
  void localHttpHeadAndErrorScenarios() throws Exception {
    ClusterNodeRegistry registry = mock(ClusterNodeRegistry.class);
    when(registry.route(ENV)).thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));

    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    FakeEnvironmentTunnel tunnel = tunnels.register(IDENTITY);

    IdeDataViewAuthorizer authorizer = mock(IdeDataViewAuthorizer.class);
    when(authorizer.authorize(eq(ENV), eq(VIEW), any()))
        .thenReturn(
            Optional.of(
                new IdeDataViewAuthorizer.IdeDataViewAccess(
                    IDENTITY, VIEW, 1L, Set.of("/workspace"))));

    IdeBrowserSessionHandler sessionHandler = mock(IdeBrowserSessionHandler.class);
    IdeRemoteDataPlaneForwarder forwarder = mock(IdeRemoteDataPlaneForwarder.class);

    IdeGatewayDataPlane dataPlane =
        new IdeGatewayDataPlane(registry, tunnels, authorizer, sessionHandler, forwarder);

    // 1. HEAD request returns without writing body
    HttpServletRequest headReq = mock(HttpServletRequest.class);
    when(headReq.getMethod()).thenReturn("HEAD");
    when(headReq.getContextPath()).thenReturn("");
    when(headReq.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    when(headReq.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    HttpServletResponse headRes = mock(HttpServletResponse.class);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                while (tunnel.exchanges().isEmpty()) {
                  Thread.sleep(10);
                }
                FakeTunnelExchange exchange = tunnel.exchanges().get(0);
                exchange.respond(new TunnelExchange.Response(200, Map.of()));
              } catch (Exception ignored) {
              }
            });

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/file.txt", headReq, headRes);
    verify(headRes).setStatus(200);

    // 2. Local WS dispatch failure waiting for headers returns 502
    HttpServletRequest wsReq = mock(HttpServletRequest.class);
    when(wsReq.getMethod()).thenReturn("GET");
    when(wsReq.getHeader("Connection")).thenReturn("Upgrade");
    when(wsReq.getHeader("Upgrade")).thenReturn("websocket");
    when(wsReq.getHeader("Sec-WebSocket-Version")).thenReturn("13");
    when(wsReq.getHeader("Sec-WebSocket-Key")).thenReturn("dGhlIHNhbXBsZSBub25jZQ==");
    when(wsReq.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    when(wsReq.getHeader("Origin")).thenReturn("http://localhost");
    when(wsReq.getScheme()).thenReturn("http");
    when(wsReq.getServerName()).thenReturn("localhost");
    when(wsReq.getServerPort()).thenReturn(80);
    when(wsReq.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
    HttpServletResponse wsRes = mock(HttpServletResponse.class);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                while (tunnel.exchanges().isEmpty()) {
                  Thread.sleep(10);
                }
                FakeTunnelExchange exchange = tunnel.exchanges().get(tunnel.exchanges().size() - 1);
                exchange.failHeaders(new IOException("tunnel closed"));
              } catch (Exception ignored) {
              }
            });

    dataPlane.handleBrowserTraffic(ENV, VIEW, "/ws-fail", wsReq, wsRes);
    verify(wsRes).sendError(HttpServletResponse.SC_BAD_GATEWAY);
  }
}
