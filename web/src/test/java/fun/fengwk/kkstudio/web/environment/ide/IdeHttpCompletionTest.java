package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;
import fun.fengwk.kkstudio.ide.mux.*;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

class IdeHttpCompletionTest {
  @ParameterizedTest
  @ValueSource(strings = {"success", "early", "write-failure"})
  void completionUsesActualWriteResultAndContainsEarlyResponse(String mode) throws Exception {
    // COMPLETE closes the real mux stream before complete() returns to the HTTP adapter.
    EnvironmentId env = EnvironmentId.parse("55555555-5555-5555-5555-555555555555");
    UUID view = UUID.randomUUID();
    IdeDataConnectionRegistry registry = mock(IdeDataConnectionRegistry.class);
    MuxWireCodec codec = new MuxWireCodec();
    MuxConnection[] connection = new MuxConnection[1];
    connection[0] =
        new MuxConnection(
            new MuxTransport() {
              public CompletionStage<Void> write(byte[] wire) {
                MuxFrame frame = codec.decode(wire);
                if (frame instanceof MuxFrame.Complete && mode.equals("write-failure")) {
                  return CompletableFuture.failedFuture(new IllegalStateException("write failed"));
                }
                return CompletableFuture.completedFuture(null);
              }

              public void close() {}
            },
            mock(MuxConnectionListener.class));
    when(registry.openHttp(
            any(), any(), anyLong(), anyString(), anyString(), anyList(), any(), any()))
        .thenAnswer(
            call -> {
              MuxStream stream =
                  connection[0].open(
                      MuxOpen.http(view, 1, "GET", "/", List.of()), call.getArgument(7));
              connection[0].receive(
                  codec.encode(new MuxFrame.Ready(stream.streamId().orElseThrow())));
              return stream;
            });
    ClusterNodeRegistry nodes = mock(ClusterNodeRegistry.class);
    when(nodes.route(env)).thenReturn(Optional.of(ClusterOwnerRoute.local(UUID.randomUUID())));
    IdeDataViewAuthorizer authorizer =
        (e, v, cookie) ->
            Optional.of(
                new IdeDataViewAuthorizer.IdeDataViewAccess(
                    new DaemonConnectionIdentity(env, "test"), view, 1, Set.of()));
    IdeGatewayDataPlane plane =
        new IdeGatewayDataPlane(
            mock(IdeCommandTransport.class),
            new IdeGatewayProperties(),
            nodes,
            mock(IdeRemoteDataPlaneForwarder.class),
            registry,
            authorizer,
            mock(IdeBrowserSessionHandler.class));
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
    request.addHeader("Sec-Fetch-Site", "same-origin");
    MockHttpServletResponse response = new MockHttpServletResponse();
    Class<?> type = Class.forName(IdeGatewayDataPlane.class.getName() + "$HttpExchange");
    var constructor = type.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object exchange = constructor.newInstance(plane, env, view, request, response);
    assertOrderedDrain(
        exchange,
        () -> ReflectionTestUtils.invokeMethod(exchange, "attach", mock(MuxStream.class)));
    IdeBrowserDataStream ws =
        new IdeBrowserDataStream(
            mock(WebSocketSession.class),
            env,
            view,
            1,
            "/",
            List.of(),
            new DaemonConnectionIdentity(env, "test"),
            registry,
            () -> {});
    assertOrderedDrain(
        ws, () -> ReflectionTestUtils.invokeMethod(ws, "attach", mock(MuxStream.class)));
    MuxStream stream =
        connection[0].open(
            MuxOpen.http(view, 1, "GET", "/", List.of()), (MuxStreamListener) exchange);
    ReflectionTestUtils.invokeMethod(exchange, "attach", stream);
    stream
        .closed()
        .whenComplete((v, e) -> ReflectionTestUtils.invokeMethod(exchange, "onStreamClosed", e));
    long id = stream.streamId().orElseThrow();
    connection[0].receive(codec.encode(new MuxFrame.Ready(id)));
    if (!mode.equals("early")) {
      stream.sendEnd().join();
    }
    connection[0].receive(codec.encode(new MuxFrame.Headers(id, MuxHeaders.http(200, List.of()))));
    connection[0].receive(codec.encode(new MuxFrame.End(id)));
    CompletableFuture<?> terminal =
        (CompletableFuture<?>) ReflectionTestUtils.getField(exchange, "terminal");
    assertEquals(!mode.equals("success"), terminal.isCompletedExceptionally());
    assertTrue(terminal.isDone());
    assertEquals(200, response.getStatus());
    assertEquals(0, connection[0].activeStreamCount());
    assertEquals(!mode.equals("write-failure"), connection[0].isOpen());
  }

  private void assertOrderedDrain(Object adapter, Runnable attach) {
    List<String> order = new ArrayList<>();
    Runnable last = () -> order.add("C");
    Runnable first =
        () -> {
          order.add("A");
          ReflectionTestUtils.invokeMethod(adapter, "dispatch", last);
        };
    ReflectionTestUtils.invokeMethod(adapter, "dispatch", first);
    ReflectionTestUtils.invokeMethod(adapter, "dispatch", (Runnable) () -> order.add("B"));
    attach.run();
    assertEquals(List.of("A", "B", "C"), order);
  }
}
