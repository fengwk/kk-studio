package fun.fengwk.kkstudio.web.environment.ide;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 运行已构建 Daemon 传输模块的 Node 24 子进程，并通过 stdin/stdout 传递私有一次性配置和快照。
 *
 * <p>ticket 只经 stdin 传入，不进入 argv；子进程报告只含 loopback 测试路径和摘要，不含 ticket 或 streamUrl。测试结束会先请求
 * shutdown，再等待进程退出，超时才强制终止。
 */
final class MuxInteropNodeProcess implements AutoCloseable {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);

  private final Process process;
  private final BufferedWriter stdin;
  private final BlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
  private final List<String> stdoutHistory = Collections.synchronizedList(new ArrayList<>());
  private final List<String> stderrHistory = Collections.synchronizedList(new ArrayList<>());
  private final Thread stdoutReader;
  private final Thread stderrReader;

  private boolean closed;

  private MuxInteropNodeProcess(Process process) {
    this.process = process;
    this.stdin =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    this.stdoutReader = Thread.ofVirtual().name("mux-interop-node-stdout").start(this::readStdout);
    this.stderrReader = Thread.ofVirtual().name("mux-interop-node-stderr").start(this::readStderr);
  }

  static MuxInteropNodeProcess start(
      Path repoRoot,
      String studioUrl,
      String streamUrl,
      String ticket,
      UUID viewId,
      long bindingEpoch)
      throws IOException, URISyntaxException {
    Path daemonDist = repoRoot.resolve("daemon/dist");
    if (!Files.isRegularFile(daemonDist.resolve("transport/mux-upstream.js"))) {
      throw new IllegalStateException(
          "daemon/dist is missing; run `npm --prefix daemon ci && npm --prefix daemon run build`"
              + " before this integration test");
    }
    Path fixture =
        Path.of(
            Objects.requireNonNull(
                    MuxInteropNodeProcess.class.getResource(
                        "/fun/fengwk/kkstudio/web/environment/ide/mux-node-fixture.mjs"))
                .toURI());
    ProcessBuilder builder = new ProcessBuilder(resolveNodeBinary(), fixture.toString());
    builder.directory(repoRoot.toFile());
    Process process = builder.start();
    MuxInteropNodeProcess node = new MuxInteropNodeProcess(process);
    try {
      Map<String, Object> configuration = new LinkedHashMap<>();
      configuration.put("studioUrl", studioUrl);
      configuration.put("streamUrl", streamUrl);
      configuration.put("generation", "1");
      configuration.put("ticket", ticket);
      configuration.put("daemonDist", daemonDist.toAbsolutePath().normalize().toString());
      configuration.put("viewId", viewId.toString());
      configuration.put("bindingEpoch", Long.toString(bindingEpoch));
      node.stdin.write(MAPPER.writeValueAsString(configuration));
      node.stdin.newLine();
      node.stdin.flush();
    } catch (IOException | RuntimeException error) {
      node.close();
      throw error;
    }
    return node;
  }

  /** 请求一次无凭据状态快照并等待对应响应。 */
  JsonNode snapshot(Duration timeout) throws IOException, InterruptedException {
    synchronized (this) {
      if (closed || !process.isAlive()) {
        throw new IOException(
            "mux interop node exited before a snapshot was requested" + diagnostics());
      }
      stdin.write("{\"action\":\"snapshot\"}");
      stdin.newLine();
      stdin.flush();
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
        throw new IOException("timed out waiting for a mux interop node snapshot" + diagnostics());
      }
      String line = stdoutLines.poll(remaining, TimeUnit.NANOSECONDS);
      if (line == null) {
        throw new IOException("timed out waiting for a mux interop node snapshot" + diagnostics());
      }
      JsonNode report = MAPPER.readTree(line);
      if ("snapshot".equals(report.path("type").asText())) {
        return report;
      }
    }
  }

  /** 轮询快照直到条件成立；用于等待 Node 引擎收敛而不依赖固定 sleep。 */
  JsonNode awaitSnapshot(Predicate<JsonNode> condition, Duration timeout)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    JsonNode last = null;
    while (System.nanoTime() < deadline) {
      last = snapshot(remaining(deadline));
      if (condition.test(last)) {
        return last;
      }
      Thread.sleep(20L);
    }
    throw new AssertionError(
        "mux interop node did not reach the expected state"
            + diagnostics()
            + "\nlast snapshot: "
            + last);
  }

  String diagnostics() {
    List<String> output;
    List<String> errors;
    synchronized (stdoutHistory) {
      output = tail(stdoutHistory);
    }
    synchronized (stderrHistory) {
      errors = tail(stderrHistory);
    }
    return "\nnode stdout:\n"
        + String.join("\n", output)
        + "\nnode stderr:\n"
        + String.join("\n", errors);
  }

  @Override
  public void close() {
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      if (process.isAlive()) {
        try {
          stdin.write("{\"action\":\"shutdown\"}");
          stdin.newLine();
          stdin.flush();
        } catch (IOException ignored) {
          // 子进程可能已经退出；下面统一等待并收敛。
        }
      }
      try {
        stdin.close();
      } catch (IOException ignored) {
        // 关闭已断管道是清理路径上的正常失败。
      }
    }
    try {
      if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      stdoutReader.join(2_000L);
      stderrReader.join(2_000L);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  private static Duration remaining(long deadline) {
    long nanos = Math.max(1L, deadline - System.nanoTime());
    return Duration.ofNanos(nanos);
  }

  private static List<String> tail(List<String> lines) {
    int from = Math.max(0, lines.size() - 20);
    return List.copyOf(lines.subList(from, lines.size()));
  }

  private static String resolveNodeBinary() {
    String environment = System.getenv("NODE_BIN");
    if (environment != null && !environment.isBlank()) {
      return environment;
    }
    String property = System.getProperty("node.bin");
    if (property != null && !property.isBlank()) {
      return property;
    }
    return "node";
  }

  private void readStdout() {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        stdoutHistory.add(line);
        stdoutLines.add(line);
      }
    } catch (IOException error) {
      stderrHistory.add("stdout reader failed: " + error.getMessage());
    }
  }

  private void readStderr() {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        stderrHistory.add(line);
      }
    } catch (IOException error) {
      stderrHistory.add("stderr reader failed: " + error.getMessage());
    }
  }
}
