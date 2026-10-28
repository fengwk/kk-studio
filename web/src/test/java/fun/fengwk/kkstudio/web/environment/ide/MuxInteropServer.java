package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import fun.fengwk.kkstudio.ide.mux.MuxConnection;
import fun.fengwk.kkstudio.ide.mux.MuxConnectionListener;
import fun.fengwk.kkstudio.ide.mux.MuxLimits;
import fun.fengwk.kkstudio.ide.mux.MuxTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实 Tomcat 上的 Studio 物理数据 WebSocket，只接受测试生成的一次性 ticket。
 *
 * <p>连接建立后由测试自有单写者执行器把完整二进制帧交给生产 {@link MuxConnection}。测试使用自有 transport 是为了在清理时
 * 显式等待写入执行器和入站线程收敛，再一次性删除临时目录；生产 mux engine 与 Node Daemon 进程都未被替换。
 */
final class MuxInteropServer implements AutoCloseable {

  static final String DATA_PATH = "/api/harness/environment-data/v2/testenvironment";

  private static final SecureRandom RANDOM = new SecureRandom();

  private static final Duration STREAM_CONVERGENCE_TIMEOUT = Duration.ofSeconds(5);

  private final String ticket;
  private final Tomcat tomcat;
  private final Path baseDir;
  private final DefaultHandshakeHandler handshakeHandler;
  private final PhysicalWebSocketHandler physical;
  private final CountDownLatch connected = new CountDownLatch(1);
  private final CountDownLatch disconnected = new CountDownLatch(1);
  private final AtomicReference<MuxConnection> connection = new AtomicReference<>();
  private final AtomicReference<WebSocketSession> session = new AtomicReference<>();
  private final AtomicInteger acceptedConnections = new AtomicInteger();
  private final AtomicInteger closedConnections = new AtomicInteger();

  private volatile ScheduledExecutorService utilityExecutor;
  private int port;

  private MuxInteropServer(String ticket, Tomcat tomcat, Path baseDir) {
    this.ticket = ticket;
    this.tomcat = tomcat;
    this.baseDir = baseDir;
    this.handshakeHandler = new DefaultHandshakeHandler();
    this.physical = new PhysicalWebSocketHandler();
  }

  static MuxInteropServer start() throws Exception {
    byte[] ticketBytes = new byte[32];
    RANDOM.nextBytes(ticketBytes);
    String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(ticketBytes);
    Path baseDir = Files.createTempDirectory("kk-mux-interop-tomcat-");
    Tomcat tomcat = new Tomcat();
    String previousCatalinaHome = System.getProperty("catalina.home");
    String previousCatalinaBase = System.getProperty("catalina.base");
    try {
      // Tomcat.initBaseDir 会读取并改写全局 catalina.home/base；初始化期间让两者都指向本 fixture，
      // 实例持有自己的路径后再恢复，避免下一测试复用已删除的旧目录。
      System.setProperty("catalina.home", baseDir.toString());
      System.setProperty("catalina.base", baseDir.toString());
      tomcat.setBaseDir(baseDir.toString());
      tomcat.setPort(0);
      tomcat.getConnector();
    } finally {
      restoreSystemProperty("catalina.home", previousCatalinaHome);
      restoreSystemProperty("catalina.base", previousCatalinaBase);
    }
    Context context = tomcat.addContext("", baseDir.toString());
    context.addServletContainerInitializer(new WsSci(), null);
    MuxInteropServer server = new MuxInteropServer(ticket, tomcat, baseDir);
    Tomcat.addServlet(
        context,
        "muxInteropData",
        new HttpServlet() {
          @Override
          protected void service(HttpServletRequest request, HttpServletResponse response)
              throws ServletException, IOException {
            if (!server.matchesTicket(request.getParameter("ticket"))) {
              response.sendError(HttpServletResponse.SC_FORBIDDEN);
              return;
            }
            server.handshakeHandler.setServletContext(request.getServletContext());
            server.handshakeHandler.start();
            new WebSocketHttpRequestHandler(server.physical, server.handshakeHandler)
                .handleRequest(request, response);
          }
        });
    context.addServletMappingDecoded(DATA_PATH, "muxInteropData");
    try {
      tomcat.start();
      server.utilityExecutor = tomcat.getServer().getUtilityExecutor();
      server.port = tomcat.getConnector().getLocalPort();
      return server;
    } catch (Exception error) {
      server.close();
      throw error;
    }
  }

  String ticket() {
    return ticket;
  }

  String studioUrl() {
    return "http://127.0.0.1:" + port;
  }

  String streamUrl() {
    return studioUrl() + DATA_PATH;
  }

  MuxConnection awaitConnection(Duration timeout) throws InterruptedException {
    if (!connected.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new AssertionError("physical mux WebSocket did not connect");
    }
    return Objects.requireNonNull(connection.get(), "mux connection");
  }

  void disconnect() throws IOException {
    WebSocketSession current = session.get();
    if (current != null) {
      current.close(CloseStatus.SERVER_ERROR.withReason("test physical disconnect"));
    }
  }

  void awaitDisconnected(Duration timeout) throws InterruptedException {
    if (!disconnected.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new AssertionError("physical mux WebSocket did not close");
    }
  }

  int acceptedConnections() {
    return acceptedConnections.get();
  }

  int closedConnections() {
    return closedConnections.get();
  }

  @Override
  public void close() {
    List<Throwable> failures = new ArrayList<>();
    closePhysical(failures);
    try {
      tomcat.stop();
    } catch (Exception error) {
      failures.add(error);
    }
    try {
      tomcat.destroy();
    } catch (Exception error) {
      failures.add(error);
    }
    ScheduledExecutorService currentUtility = utilityExecutor;
    if (currentUtility != null) {
      currentUtility.shutdownNow();
      try {
        if (!currentUtility.awaitTermination(
            STREAM_CONVERGENCE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          failures.add(new IllegalStateException("Tomcat utility executor did not terminate"));
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        failures.add(error);
      }
    }
    try {
      handshakeHandler.stop();
    } catch (RuntimeException error) {
      failures.add(error);
    }
    try {
      deleteRecursively(baseDir);
    } catch (IOException error) {
      failures.add(error);
    }
    rethrow(failures);
  }

  private static void restoreSystemProperty(String name, String value) {
    if (value == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, value);
    }
  }

  private void closePhysical(List<Throwable> failures) {
    WebSocketSession current = session.getAndSet(null);
    if (current != null) {
      try {
        if (current.isOpen()) {
          current.close(CloseStatus.GOING_AWAY.withReason("test server shutdown"));
        }
      } catch (IOException error) {
        failures.add(error);
      }
    }
    if (acceptedConnections.get() > 0) {
      try {
        if (!disconnected.await(STREAM_CONVERGENCE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          failures.add(
              new IllegalStateException("physical mux WebSocket did not converge before cleanup"));
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        failures.add(error);
      }
    }
    MuxConnection currentMux = connection.getAndSet(null);
    if (currentMux != null) {
      try {
        currentMux.close();
      } catch (RuntimeException error) {
        failures.add(error);
      }
    }
    try {
      physical.awaitStopped(STREAM_CONVERGENCE_TIMEOUT);
      IOException closeFailure = physical.closeFailure();
      if (closeFailure != null) {
        failures.add(closeFailure);
      }
    } catch (RuntimeException error) {
      failures.add(error);
    }
  }

  private static void rethrow(List<Throwable> failures) {
    if (failures.isEmpty()) {
      return;
    }
    Throwable primary = failures.getFirst();
    for (int index = 1; index < failures.size(); index++) {
      primary.addSuppressed(failures.get(index));
    }
    if (primary instanceof RuntimeException runtime) {
      throw runtime;
    }
    if (primary instanceof Error error) {
      throw error;
    }
    throw new IllegalStateException("mux interop server cleanup failed", primary);
  }

  private boolean matchesTicket(String candidate) {
    if (candidate == null) {
      return false;
    }
    return MessageDigest.isEqual(
        ticket.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    List<Path> paths;
    try (var walk = Files.walk(directory)) {
      paths = walk.sorted(Comparator.reverseOrder()).toList();
    }
    IOException failure = null;
    for (Path path : paths) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException error) {
        if (failure == null) {
          failure = error;
        } else {
          failure.addSuppressed(error);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** 一个真实物理连接对应一个 Java mux peer。 */
  private final class PhysicalWebSocketHandler extends BinaryWebSocketHandler {

    private volatile TestWebSocketTransport transport;
    private volatile MuxConnection mux;
    private volatile Thread inbound;

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocketSession) throws Exception {
      if (!session.compareAndSet(null, webSocketSession)) {
        webSocketSession.close(
            CloseStatus.POLICY_VIOLATION.withReason("duplicate data connection"));
        return;
      }
      acceptedConnections.incrementAndGet();
      TestWebSocketTransport newTransport = new TestWebSocketTransport(webSocketSession);
      MuxConnection newMux = new MuxConnection(newTransport, new MuxConnectionListener() {});
      transport = newTransport;
      mux = newMux;
      connection.set(newMux);
      inbound =
          Thread.ofVirtual()
              .name("mux-interop-inbound-" + webSocketSession.getId())
              .start(this::readInbound);
      connected.countDown();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession webSocketSession, BinaryMessage message) {
      byte[] frame = new byte[message.getPayloadLength()];
      message.getPayload().get(frame);
      TestWebSocketTransport currentTransport = transport;
      MuxConnection currentMux = mux;
      if (currentTransport == null || currentMux == null) {
        return;
      }
      if (!currentTransport.offer(frame)) {
        currentMux.fail(new IOException("mux inbound queue rejected a frame"));
      }
    }

    @Override
    public void handleTransportError(WebSocketSession webSocketSession, Throwable exception) {
      MuxConnection currentMux = mux;
      if (currentMux != null) {
        currentMux.fail(exception);
      }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocketSession, CloseStatus status) {
      closedConnections.incrementAndGet();
      TestWebSocketTransport currentTransport = transport;
      if (currentTransport != null) {
        currentTransport.onRemoteClosed();
      }
      MuxConnection currentMux = mux;
      if (currentMux != null) {
        currentMux.fail(new IOException("physical data websocket closed"));
      }
      Thread currentInbound = inbound;
      if (currentInbound != null) {
        currentInbound.interrupt();
      }
      session.compareAndSet(webSocketSession, null);
      disconnected.countDown();
    }

    private void awaitStopped(Duration timeout) {
      Thread currentInbound = inbound;
      if (currentInbound != null) {
        try {
          currentInbound.join(timeout.toMillis());
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "interrupted while waiting for mux inbound shutdown", error);
        }
        if (currentInbound.isAlive()) {
          throw new IllegalStateException("mux inbound thread did not terminate");
        }
      }
      TestWebSocketTransport currentTransport = transport;
      if (currentTransport != null) {
        try {
          currentTransport.awaitTermination(timeout);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "interrupted while waiting for mux writer shutdown", error);
        }
      }
    }

    private IOException closeFailure() {
      TestWebSocketTransport currentTransport = transport;
      return currentTransport == null ? null : currentTransport.closeFailure();
    }

    private void readInbound() {
      TestWebSocketTransport currentTransport = transport;
      MuxConnection currentMux = mux;
      try {
        while (true) {
          byte[] frame = currentTransport.poll(1_000L);
          if (frame == null) {
            if (currentTransport.isClosed()) {
              return;
            }
            continue;
          }
          currentMux.receive(frame);
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      } catch (Throwable error) {
        currentMux.fail(error);
      }
    }
  }

  /** 测试侧单写者物理传输：写入执行器和入站队列都由测试拥有，便于在删除临时目录前证明两者已经终止。 */
  private static final class TestWebSocketTransport implements MuxTransport {

    private static final int MAX_MESSAGE_BYTES =
        MuxLimits.HEADER_BYTES + MuxLimits.MAX_PAYLOAD_BYTES;

    private static final int MAX_QUEUED_FRAMES = 256;

    private final WebSocketSession session;
    private final ExecutorService writer;
    private final ArrayBlockingQueue<byte[]> inbound = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object sendLock = new Object();

    private volatile IOException closeFailure;

    private TestWebSocketTransport(WebSocketSession session) {
      this.session = session;
      this.writer =
          Executors.newSingleThreadExecutor(
              Thread.ofVirtual().name("mux-interop-writer-" + session.getId()).factory());
    }

    @Override
    public CompletionStage<Void> write(byte[] frame) {
      CompletableFuture<Void> completion = new CompletableFuture<>();
      if (frame.length > MAX_MESSAGE_BYTES) {
        completion.completeExceptionally(
            new IllegalArgumentException("data frame exceeds " + MAX_MESSAGE_BYTES + " bytes"));
        return completion;
      }
      try {
        writer.execute(
            () -> {
              if (closed.get()) {
                completion.completeExceptionally(
                    new IllegalStateException("data transport is closed"));
                return;
              }
              try {
                synchronized (sendLock) {
                  if (closed.get()) {
                    throw new IllegalStateException("data transport is closed");
                  }
                  session.sendMessage(new BinaryMessage(frame, true));
                }
                completion.complete(null);
              } catch (Throwable error) {
                closed.set(true);
                completion.completeExceptionally(error);
                closeSession(CloseStatus.SERVER_ERROR.withReason("data write failed"));
              }
            });
      } catch (RejectedExecutionException error) {
        completion.completeExceptionally(error);
      }
      return completion;
    }

    boolean offer(byte[] frame) {
      if (closed.get()) {
        return false;
      }
      return inbound.offer(frame);
    }

    byte[] poll(long timeoutMillis) throws InterruptedException {
      if (timeoutMillis <= 0L) {
        return inbound.poll();
      }
      return inbound.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    boolean isClosed() {
      return closed.get();
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        writer.shutdownNow();
        closeSession(CloseStatus.GOING_AWAY.withReason("data connection closed"));
      }
    }

    void onRemoteClosed() {
      closed.set(true);
      writer.shutdownNow();
    }

    void awaitTermination(Duration timeout) throws InterruptedException {
      if (!writer.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("mux writer executor did not terminate");
      }
    }

    IOException closeFailure() {
      return closeFailure;
    }

    private void closeSession(CloseStatus status) {
      synchronized (sendLock) {
        if (!session.isOpen()) {
          return;
        }
        try {
          session.close(status);
        } catch (IOException error) {
          closeFailure = error;
        }
      }
    }
  }
}
