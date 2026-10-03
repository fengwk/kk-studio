package fun.fengwk.kkstudio.harness.daemon.skill;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 本地 dumb Git HTTP fixture：真实 loose objects，可控的广告分片或 socket 停滞，不访问外网。 */
final class LocalGitHttpServer implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final Path gitDirectory;
  private final byte[] advertisement;
  final AtomicInteger requests = new AtomicInteger();
  final CountDownLatch entered = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  volatile boolean stall;
  volatile boolean slow;

  LocalGitHttpServer(Path gitDirectory, String commit) throws IOException {
    this.gitDirectory = gitDirectory;
    advertisement = (commit + "\trefs/heads/main\n").getBytes(StandardCharsets.UTF_8);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(workers);
    server.createContext("/", this::serve);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  private void serve(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    String path = exchange.getRequestURI().getPath();
    byte[] body;
    if (path.equals("/info/refs")) {
      body = advertisement;
    } else if (path.equals("/HEAD")) {
      body = "ref: refs/heads/main\n".getBytes(StandardCharsets.UTF_8);
    } else {
      Path file = gitDirectory.resolve(path.substring(1)).normalize();
      if (!file.startsWith(gitDirectory) || !Files.isRegularFile(file)) {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
        return;
      }
      body = Files.readAllBytes(file);
    }
    exchange.getResponseHeaders().set("Content-Type", "text/plain");
    exchange.sendResponseHeaders(200, body.length);
    entered.countDown();
    try {
      if (stall) {
        release.await();
      } else if (slow && path.equals("/info/refs")) {
        AtomicInteger index = new AtomicInteger();
        CompletableFuture<Void> complete = new CompletableFuture<>();
        var writer =
            scheduler.scheduleAtFixedRate(
                () -> {
                  int offset = index.getAndAdd(5);
                  try {
                    if (offset >= body.length) {
                      complete.complete(null);
                    } else {
                      exchange
                          .getResponseBody()
                          .write(body, offset, Math.min(5, body.length - offset));
                      exchange.getResponseBody().flush();
                    }
                  } catch (IOException error) {
                    complete.completeExceptionally(error);
                  }
                },
                0,
                50,
                TimeUnit.MILLISECONDS);
        try {
          complete.join();
        } finally {
          writer.cancel(false);
        }
      } else {
        exchange.getResponseBody().write(body);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  @Override
  public void close() {
    release.countDown();
    server.stop(0);
    scheduler.shutdownNow();
    workers.shutdownNow();
  }
}
