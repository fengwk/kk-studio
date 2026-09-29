package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * LSP 测试基座：启动 {@link FakeLspServer} 子进程、构造对应配置，并读取它写下的消息记录。
 *
 * <p>所有断言都基于真实子进程与真实 stdio 帧，不依赖任何 mock：这样“协议通过”才成立。
 */
final class FakeLspServers {

  static final Duration WAIT = Duration.ofSeconds(20);

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final AtomicInteger TRANSCRIPTS = new AtomicInteger();

  private FakeLspServers() {}

  /** 直接以 JVM 启动假服务器；命令不叫 jdtls，因此不会被当作 jdtls。 */
  static LspServerConfig javaServer(String id, String mode, Path root, Path transcript) {
    List<String> command = new ArrayList<>();
    command.add(javaExecutable());
    command.add("-cp");
    command.add(childClasspath());
    command.add(FakeLspServer.class.getName());
    command.add(mode);
    command.add(transcript.toString());
    return new LspServerConfig(
        id, command, List.of(".java", ".txt"), List.of("pom.xml"), List.of());
  }

  /** 以名为 {@code jdtls} 的包装脚本启动假服务器：用于验证 jdtls 专属行为与包装脚本识别。 */
  static LspServerConfig jdtlsServer(Path root, String mode, Path transcript, Path childPidFile)
      throws IOException {
    Path wrapper = root.resolve("jdtls");
    StringBuilder script = new StringBuilder("#!/bin/sh\nexec ");
    script
        .append(quote(javaExecutable()))
        .append(" -cp ")
        .append(quote(childClasspath()))
        .append(' ')
        .append(FakeLspServer.class.getName())
        .append(' ')
        .append(mode)
        .append(' ')
        .append(quote(transcript.toString()));
    if (childPidFile != null) {
      script.append(' ').append(quote(childPidFile.toString()));
    }
    script.append('\n');
    Files.writeString(wrapper, script.toString(), StandardCharsets.UTF_8);
    if (!wrapper.toFile().setExecutable(true)) {
      throw new IllegalStateException("cannot mark wrapper executable: " + wrapper);
    }
    return new LspServerConfig(
        "java", List.of(wrapper.toString()), List.of(".java"), List.of("pom.xml"), List.of());
  }

  /** 立即退出的"服务器"：用 shell 而不是 JVM，保证启动探测能确定性观察到退出。 */
  static LspServerConfig deadServer(String id, int exitCode) {
    return new LspServerConfig(
        id, List.of("/bin/sh", "-c", "exit " + exitCode), List.of(".java"), List.of(), List.of());
  }

  /**
   * 立即退出、并且留下一个忽略 {@code TERM} 的子进程的"服务器"：用于验证启动失败时整棵范围都被收敛，而不只是服务器自己。
   *
   * <p>子进程的 pid 由 shell 在退出之前写下，因此「启动失败」这一事实成立时，pid 一定已经可读。
   */
  static LspServerConfig deadServerWithStubbornChild(String id, int exitCode, Path childPid) {
    String script =
        "(trap '' TERM; sleep 600) & echo $! > "
            + quote(childPid.toString())
            + "; exit "
            + exitCode;
    return new LspServerConfig(
        id, List.of("/bin/sh", "-c", script), List.of(".java"), List.of(), List.of());
  }

  static LspDiscovery discovery(LspServerConfig... servers) {
    return LspDiscovery.of(List.of(servers));
  }

  static Path transcript(Path root) {
    return root.resolve("lsp-transcript-" + TRANSCRIPTS.incrementAndGet() + ".jsonl");
  }

  /** 轮询消息记录直到出现满足条件的事件；超时直接断言失败。 */
  static JsonNode await(Path transcript, Predicate<JsonNode> predicate) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      for (JsonNode event : events(transcript)) {
        if (predicate.test(event)) {
          return event;
        }
      }
      sleep(20);
    }
    throw new AssertionError(
        "transcript " + transcript + " never matched; recorded: " + events(transcript));
  }

  static List<JsonNode> events(Path transcript) {
    if (!Files.exists(transcript)) {
      return List.of();
    }
    try {
      List<JsonNode> events = new ArrayList<>();
      for (String line : Files.readAllLines(transcript, StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          events.add(MAPPER.readTree(line));
        }
      }
      return events;
    } catch (IOException error) {
      throw new AssertionError(error);
    }
  }

  /** 收到的方法调用（客户端发给服务器的请求与通知）。 */
  static List<JsonNode> received(Path transcript, String method) {
    List<JsonNode> matched = new ArrayList<>();
    for (JsonNode event : events(transcript)) {
      if (event.path("event").asText().equals("recv")
          && method.equals(event.path("message").path("method").asText())) {
        matched.add(event.path("message"));
      }
    }
    return matched;
  }

  /** 服务器收到的应答（服务端请求的应答）。 */
  static List<JsonNode> responses(Path transcript) {
    List<JsonNode> matched = new ArrayList<>();
    for (JsonNode event : events(transcript)) {
      if (event.path("event").asText().equals("recv")
          && event.path("message").path("method").isMissingNode()) {
        matched.add(event.path("message"));
      }
    }
    return matched;
  }

  static long startedPid(Path transcript) {
    return await(transcript, event -> event.path("event").asText().equals("start"))
        .path("pid")
        .asLong();
  }

  /** 轮询直到进程消失；用于断言回收与强制终止真的发生。 */
  static void awaitProcessGone(long pid, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false) {
        return;
      }
      sleep(20);
    }
    throw new AssertionError("process " + pid + " is still alive");
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  /** 子 JVM 的 classpath：surefire 的 manifest-only jar 同样被展开成真实条目。 */
  static String childClasspath() {
    ClassLoader loader = FakeLspServers.class.getClassLoader();
    if (loader instanceof URLClassLoader urlLoader) {
      URL[] urls = urlLoader.getURLs();
      if (urls.length > 0) {
        return List.of(urls).stream()
            .map(
                url -> {
                  try {
                    return Path.of(url.toURI()).toString();
                  } catch (URISyntaxException error) {
                    throw new AssertionError(error);
                  }
                })
            .collect(Collectors.joining(File.pathSeparator));
      }
    }
    return System.getProperty("java.class.path");
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
