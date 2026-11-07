package fun.fengwk.kkstudio.platform.daemon.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.tunnel.testing.FakeEnvironmentTunnel;
import fun.fengwk.kkstudio.platform.daemon.tunnel.testing.FakeEnvironmentTunnels;
import fun.fengwk.kkstudio.platform.daemon.tunnel.testing.FakeTunnelExchange;
import fun.fengwk.kkstudio.platform.daemon.tunnel.testing.FakeTunnelStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** 用最小 fake 锁定隧道端口的生命周期、背压、头不可变和代际不迁移。 */
class TunnelPortContractTest {

  private static final EnvironmentId ENVIRONMENT =
      EnvironmentId.parse("11111111-1111-4111-8111-111111111111");

  @Test
  void localReturnsOnlyReadyUndrainedSession() {
    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    assertTrue(tunnels.local(ENVIRONMENT).isEmpty());

    FakeEnvironmentTunnel tunnel = register(tunnels, "daemon-a");
    assertSame(tunnel, tunnels.local(ENVIRONMENT).orElseThrow());

    TunnelExchange retained = tunnel.openWebSocket(wsRequest());
    tunnel.drain();
    assertTrue(tunnels.local(ENVIRONMENT).isEmpty());
    assertThrows(IllegalStateException.class, () -> tunnel.openWebSocket(wsRequest()));
    retained.close();
  }

  @Test
  void concurrentDirectionsAndDuplicateOperationRejection() throws Exception {
    FakeTunnelStream stream = open(register(new FakeEnvironmentTunnels(), "daemon-a"));
    FakeTunnelStream.Peer peer = stream.peer();
    CompletionStage<ByteBuffer> inbound = stream.read();
    CompletionStage<Void> outbound = stream.write(bytes("out"));
    assertFalse(outbound.toCompletableFuture().isDone());
    assertThrows(IllegalStateException.class, stream::read);
    assertThrows(IllegalStateException.class, () -> stream.write(bytes("again")));

    peer.write(bytes("in")).toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertEquals("in", text(inbound.toCompletableFuture().get(2, TimeUnit.SECONDS)));
    peer.read().toCompletableFuture().get(2, TimeUnit.SECONDS);
    outbound.toCompletableFuture().get(2, TimeUnit.SECONDS);
  }

  @Test
  void slowReaderKeepsWriterPendingUntilCapacityIsReleased() throws Exception {
    FakeTunnelStream stream = open(register(new FakeEnvironmentTunnels(), "daemon-a"));
    FakeTunnelStream.Peer peer = stream.peer();
    ByteBuffer first = bytes("one");
    ByteBuffer blocked = bytes("two");

    CompletionStage<Void> pending = stream.write(first);
    assertFalse(pending.toCompletableFuture().isDone());
    assertThrows(IllegalStateException.class, () -> stream.write(blocked));
    assertFalse(pending.toCompletableFuture().isDone());
    assertEquals(0, blocked.position());

    assertEquals("one", text(peer.read().toCompletableFuture().get(2, TimeUnit.SECONDS)));
    pending.toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertEquals(first.limit(), first.position());
  }

  @Test
  void largeWriteCompletesOnlyAfterEveryBoundedSegmentIsRead() throws Exception {
    FakeTunnelStream stream = open(register(new FakeEnvironmentTunnels(), "daemon-a"));
    ByteBuffer source = ByteBuffer.allocate(FakeTunnelStream.MAX_SEGMENT_BYTES + 3);
    CompletionStage<Void> pending = stream.write(source);
    FakeTunnelStream.Peer peer = stream.peer();

    ByteBuffer first = peer.read().toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertEquals(FakeTunnelStream.MAX_SEGMENT_BYTES, first.remaining());
    assertFalse(pending.toCompletableFuture().isDone());
    ByteBuffer rest = peer.read().toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertEquals(3, rest.remaining());
    pending.toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertEquals(source.limit(), source.position());
  }

  @Test
  void halfClosePreservesInputAndRepeatedEofIsCompletedNull() throws Exception {
    FakeTunnelStream stream = open(register(new FakeEnvironmentTunnels(), "daemon-a"));
    FakeTunnelStream.Peer peer = stream.peer();
    CompletionStage<Void> output = stream.write(bytes("out"));
    CompletionStage<Void> ending = stream.endOutput();
    assertFalse(ending.toCompletableFuture().isDone());
    assertSame(ending, stream.endOutput());

    assertEquals("out", text(peer.read().toCompletableFuture().get(2, TimeUnit.SECONDS)));
    output.toCompletableFuture().get(2, TimeUnit.SECONDS);
    ending.toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertThrows(IllegalStateException.class, () -> stream.write(bytes("late")));

    CompletionStage<ByteBuffer> pendingInput = stream.read();
    peer.write(bytes("still-open")).toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertEquals("still-open", text(pendingInput.toCompletableFuture().get(2, TimeUnit.SECONDS)));
    peer.endOutput().toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertNull(stream.read().toCompletableFuture().get(2, TimeUnit.SECONDS));
    assertNull(stream.read().toCompletableFuture().getNow(bytes("unused")));
  }

  @Test
  void closeCancelsPendingAndDisconnectFailsAllStreams() {
    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    FakeEnvironmentTunnel tunnel = register(tunnels, "daemon-a");
    TunnelStream cancelled = tunnel.openWebSocket(wsRequest()).stream();
    CompletionStage<ByteBuffer> pendingRead = cancelled.read();
    CompletionStage<Void> pendingWrite = cancelled.write(bytes("cancel"));
    cancelled.close();
    cancelled.close();
    assertThrows(CancellationException.class, () -> join(pendingRead));
    assertThrows(CancellationException.class, () -> join(pendingWrite));
    assertThrows(CancellationException.class, () -> join(cancelled.read()));

    // 关闭也取消尚未完成的半关闭观察：消费方读到 EOF 后立即关闭交换时，对端的 endOutput 不会正常完成，
    // 因此谁也不能把 endOutput 的完成当作“响应已交付”的证据。
    TunnelStream halfClosed = tunnel.openWebSocket(wsRequest()).stream();
    CompletionStage<Void> blockedWrite = halfClosed.write(bytes("half-close"));
    CompletionStage<Void> halfClose = halfClosed.endOutput();
    assertFalse(halfClose.toCompletableFuture().isDone());
    halfClosed.close();
    assertThrows(CancellationException.class, () -> join(blockedWrite));
    assertThrows(CancellationException.class, () -> join(halfClose));

    tunnels.remove(ENVIRONMENT);
    FakeEnvironmentTunnel disconnectedSession = register(tunnels, "daemon-a");
    FakeTunnelStream disconnected =
        (FakeTunnelStream) disconnectedSession.openWebSocket(wsRequest()).stream();
    CompletionStage<Void> blocked = disconnected.write(bytes("pending"));
    assertThrows(IllegalStateException.class, () -> disconnected.write(bytes("blocked")));
    disconnectedSession.disconnect();
    disconnectedSession.disconnect();
    assertThrows(IOException.class, () -> join(blocked));
    assertThrows(IOException.class, () -> join(disconnected.read()));
    assertTrue(tunnels.local(ENVIRONMENT).isEmpty());
    assertThrows(IllegalStateException.class, () -> disconnectedSession.openWebSocket(wsRequest()));
  }

  @Test
  void disconnectFailsPendingHttpWithoutReplacingSubmittedResponse() {
    FakeEnvironmentTunnel tunnel = register(new FakeEnvironmentTunnels(), "daemon-a");
    TunnelHttpRequest captured = request();
    FakeTunnelExchange pending = (FakeTunnelExchange) tunnel.openHttp(captured);
    CompletionStage<Void> body = pending.stream().write(bytes("pending"));
    FakeTunnelExchange submitted = (FakeTunnelExchange) tunnel.openHttp(request());
    TunnelExchange.Response response = new TunnelExchange.Response(201, Map.of());
    submitted.respond(response);

    tunnel.disconnect();
    assertThrows(IOException.class, () -> join(pending.responseHeaders()));
    assertThrows(IOException.class, () -> join(body));
    assertSame(response, submitted.responseHeaders().toCompletableFuture().getNow(null));
    assertSame(captured, pending.request());
    assertEquals(2, tunnel.exchanges().size());
    assertThrows(IllegalStateException.class, () -> tunnel.openHttp(request()));
  }

  @Test
  void httpExchangeKeepsNonSuccessAndRejectsInjectedHeaders() throws Exception {
    FakeEnvironmentTunnel tunnel = register(new FakeEnvironmentTunnels(), "daemon-a");
    TunnelHttpRequest request =
        new TunnelHttpRequest("POST", "/v1/files?x=1", Map.of("X-Trace", List.of("ok")));
    assertThrows(
        UnsupportedOperationException.class, () -> request.headers().put("x", List.of("y")));
    assertThrows(
        IllegalArgumentException.class, () -> new TunnelHttpRequest("PO ST", "/v1", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TunnelHttpRequest("GET", "https://host/v1", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TunnelHttpRequest("GET", "/v1", Map.of("Bad\rName", List.of("a"))));
    assertThrows(
        IllegalArgumentException.class, () -> new TunnelHttpRequest(null, "/v1", Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new TunnelHttpRequest("GET", null, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new TunnelHttpRequest("GET", "/v1", null));
    assertEquals(
        "/v1?url=https://example",
        new TunnelHttpRequest("GET", "/v1?url=https://example", Map.of()).path());
    assertThrows(
        IllegalArgumentException.class, () -> new TunnelHttpRequest("GET", "/v1 bad", Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new TunnelHttpRequest("GET", "/v1/%ZZ", Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new TunnelHttpRequest("", "/v1", Map.of()));
    Map<String, List<String>> duplicated = new LinkedHashMap<>();
    duplicated.put("X-Trace", List.of("a"));
    duplicated.put("x-trace", List.of("b"));
    assertThrows(
        IllegalArgumentException.class, () -> new TunnelHttpRequest("GET", "/v1", duplicated));
    assertThrows(
        IllegalArgumentException.class, () -> new TunnelHttpRequest("GET", "/v1#x", Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new TunnelHttpRequest("GET", "//v1", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TunnelHttpRequest("GET", "/v1", Map.of("X-Empty", List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TunnelHttpRequest("GET", "/v1", Map.of("X-Bad", List.of("a\nb"))));
    assertThrows(IllegalArgumentException.class, () -> new TunnelExchange.Response(99, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> tunnel.openWebSocket(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> tunnel.openWebSocket(new TunnelHttpRequest("POST", "/ws", Map.of())));
    assertThrows(
        IllegalArgumentException.class, () -> streamBytes(tunnel).write(ByteBuffer.allocate(0)));

    FakeTunnelExchange exchange = (FakeTunnelExchange) tunnel.openHttp(request);
    CompletionStage<TunnelExchange.Response> headers = exchange.responseHeaders();
    assertFalse(headers.toCompletableFuture().isDone());
    CompletionStage<ByteBuffer> requestBody = ((FakeTunnelStream) exchange.stream()).peer().read();
    exchange.stream().write(bytes("body"));
    requestBody.toCompletableFuture().get(2, TimeUnit.SECONDS);
    TunnelExchange.Response response =
        new TunnelExchange.Response(404, Map.of("X-Result", List.of("missing")));
    assertThrows(
        UnsupportedOperationException.class, () -> response.headers().get("x-result").add("x"));
    exchange.respond(response);
    assertEquals(404, headers.toCompletableFuture().get(2, TimeUnit.SECONDS).status());
    CompletionStage<ByteBuffer> responseBody = exchange.stream().read();
    exchange.close();
    assertThrows(CancellationException.class, () -> join(responseBody));
  }

  @Test
  void replacementDoesNotMigrateExistingHandle() throws Exception {
    FakeEnvironmentTunnels tunnels = new FakeEnvironmentTunnels();
    FakeEnvironmentTunnel original = register(tunnels, "daemon-a");
    TunnelStream stream = original.openWebSocket(wsRequest()).stream();
    DaemonConnectionIdentity first = original.identity();

    FakeEnvironmentTunnel replacement =
        tunnels.replace(new DaemonConnectionIdentity(ENVIRONMENT, "daemon-b"));
    assertEquals(
        "daemon-b", tunnels.local(ENVIRONMENT).orElseThrow().identity().daemonInstanceId());
    assertNotSame(first, replacement.identity());

    CompletionStage<ByteBuffer> retained = ((FakeTunnelStream) stream).peer().read();
    stream.write(bytes("stays")).toCompletableFuture().get(2, TimeUnit.SECONDS);
    assertSame(first, original.identity());
    assertTrue(original.draining());
    assertThrows(IllegalStateException.class, () -> original.openWebSocket(wsRequest()));
    replacement.disconnect();
    assertEquals("stays", text(retained.toCompletableFuture().get(2, TimeUnit.SECONDS)));
    assertTrue(original.streams().contains(stream));
    assertFalse(replacement.streams().contains(stream));
  }

  private static TunnelStream streamBytes(FakeEnvironmentTunnel tunnel) {
    return tunnel.openWebSocket(wsRequest()).stream();
  }

  private static FakeTunnelStream open(FakeEnvironmentTunnel tunnel) {
    return (FakeTunnelStream) tunnel.openWebSocket(wsRequest()).stream();
  }

  private static FakeEnvironmentTunnel register(
      FakeEnvironmentTunnels tunnels, String daemonInstanceId) {
    return tunnels.register(new DaemonConnectionIdentity(ENVIRONMENT, daemonInstanceId));
  }

  private static TunnelHttpRequest request() {
    return new TunnelHttpRequest("GET", "/v1", Map.of());
  }

  private static TunnelHttpRequest wsRequest() {
    return new TunnelHttpRequest("GET", "/ws", Map.of());
  }

  private static ByteBuffer bytes(String value) {
    return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String text(ByteBuffer value) {
    byte[] bytes = new byte[value.remaining()];
    value.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void join(CompletionStage<?> stage) throws Exception {
    try {
      stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof CompletionException completion && completion.getCause() != null) {
        cause = completion.getCause();
      }
      if (cause instanceof Exception exception) {
        throw exception;
      }
      throw error;
    }
  }
}
