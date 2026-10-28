package fun.fengwk.kkstudio.web.environment.ide;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.ServletOutputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry;
import fun.fengwk.kkstudio.ide.mux.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

class IdeDiscardCreditTest {
  @ParameterizedTest
  @CsvSource({"false,false", "true,false", "false,true", "true,true"})
  void discardedDataReturnsSharedCredit(boolean ws, boolean beforeAttach) throws Exception {
    MuxWireCodec codec = new MuxWireCodec();
    List<MuxFrame> writes = new ArrayList<>();
    MuxConnection connection =
        new MuxConnection(
            new MuxTransport() {
              public CompletionStage<Void> write(byte[] bytes) {
                writes.add(codec.decode(bytes));
                return CompletableFuture.completedFuture(null);
              }

              public void close() {}
            },
            mock(MuxConnectionListener.class));
    EnvironmentId env = EnvironmentId.parse("55555555-5555-5555-5555-555555555555");
    UUID view = UUID.randomUUID();
    Object adapter;
    if (ws) {
      WebSocketSession browser = mock(WebSocketSession.class);
      doThrow(new IOException("browser write failed")).when(browser).sendMessage(any());
      adapter =
          new IdeBrowserDataStream(
              browser,
              env,
              view,
              1,
              "/",
              List.of(),
              new DaemonConnectionIdentity(env, "test"),
              mock(IdeDataConnectionRegistry.class),
              () -> {});
    } else {
      IdeGatewayDataPlane plane = mock(IdeGatewayDataPlane.class);
      ReflectionTestUtils.setField(plane, "properties", new IdeGatewayProperties());
      MockHttpServletResponse response = mock(MockHttpServletResponse.class);
      ServletOutputStream output = mock(ServletOutputStream.class);
      when(response.getOutputStream()).thenReturn(output);
      doThrow(new IOException("browser write failed")).when(output).write(any(byte[].class));
      var constructor =
          Class.forName(IdeGatewayDataPlane.class.getName() + "$HttpExchange")
              .getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      adapter = constructor.newInstance(plane, env, view, new MockHttpServletRequest(), response);
    }
    MuxStream stream =
        connection.open(
            ws
                ? MuxOpen.webSocket(view, 1, "/", List.of())
                : MuxOpen.http(view, 1, "GET", "/", List.of()),
            (MuxStreamListener) adapter);
    long id = stream.streamId().orElseThrow();
    if (!beforeAttach) {
      ReflectionTestUtils.invokeMethod(adapter, "attach", stream);
    }
    connection.receive(codec.encode(new MuxFrame.Ready(id)));
    connection.receive(
        codec.encode(
            new MuxFrame.Headers(
                id, ws ? MuxHeaders.webSocket(List.of()) : MuxHeaders.http(200, List.of()))));
    connection.receive(
        codec.encode(
            new MuxFrame.Data(
                id, ws ? MuxDataKind.WS_BINARY : MuxDataKind.HTTP, ws, new byte[65536])));
    if (beforeAttach) {
      stream.reset("TEST", "closed before attach");
      if (ws) {
        ((IdeBrowserDataStream) adapter).browserFailed(new IOException("closed before attach"));
      } else {
        ReflectionTestUtils.invokeMethod(
            adapter, "onStreamClosed", new IOException("closed before attach"));
      }
      ReflectionTestUtils.invokeMethod(adapter, "attach", stream);
    }
    assertEquals(0L, ReflectionTestUtils.getField(connection, "connectionReceiveUnacked"));
    assertTrue(
        writes.stream()
            .anyMatch(
                f ->
                    f instanceof MuxFrame.Window w && w.streamId() == 0 && w.increment() == 65536));
    // The physical connection remains usable by an unrelated view after the failed stream.
    MuxStream other =
        connection.open(
            MuxOpen.http(UUID.randomUUID(), 1, "GET", "/", List.of()),
            new MuxStreamListener() {
              public void onData(MuxStream s, MuxReceivedData d) {
                d.consumed();
              }
            });
    long otherId = other.streamId().orElseThrow();
    connection.receive(codec.encode(new MuxFrame.Ready(otherId)));
    connection.receive(
        codec.encode(new MuxFrame.Headers(otherId, MuxHeaders.http(200, List.of()))));
    connection.receive(
        codec.encode(new MuxFrame.Data(otherId, MuxDataKind.HTTP, false, new byte[65536])));
    assertEquals(0L, ReflectionTestUtils.getField(connection, "connectionReceiveUnacked"));
    assertTrue(connection.isOpen());
    connection.close();
  }
}
