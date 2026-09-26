package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.agent.ConventionAgent;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 验证单独加载的 convention4j-agent 在 JDK 21 下能透传并清理线程池中的追踪 MDC。 */
class ConventionAgentPropagationTest {

  /** 在新 JVM 中启动真实探针，避免测试进程预加载 Logback 后导致字节码增强失效。 */
  @Test
  void traceIdPropagatesAcrossPrestartedWorkerWithoutLeaking() throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Path sourceAgent =
        Path.of(ConventionAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertTrue(Files.isRegularFile(sourceAgent));
    Path agentDir = Files.createTempDirectory("convention4j-agent-test-");
    Path versionedAgent = agentDir.resolve(sourceAgent.getFileName());
    Path agentAlias = agentDir.resolve("convention4j-agent.jar");
    try {
      Files.copy(sourceAgent, versionedAgent, StandardCopyOption.REPLACE_EXISTING);
      Files.createSymbolicLink(agentAlias, versionedAgent.getFileName());
      String classpath =
          Arrays.stream(
                  System.getProperty(
                          "surefire.test.class.path", System.getProperty("java.class.path"))
                      .split(Pattern.quote(File.pathSeparator)))
              .filter(entry -> !Path.of(entry).toAbsolutePath().normalize().equals(sourceAgent))
              .collect(Collectors.joining(File.pathSeparator));
      Process process =
          new ProcessBuilder(
                  java.toString(),
                  "-javaagent:" + agentAlias,
                  "-cp",
                  classpath,
                  Probe.class.getName())
              .redirectErrorStream(true)
              .start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new AssertionError("Agent propagation probe timed out");
      }
      assertEquals(
          0,
          process.exitValue(),
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    } finally {
      Files.deleteIfExists(agentAlias);
      Files.deleteIfExists(versionedAgent);
      Files.deleteIfExists(agentDir);
    }
  }

  /** 独立子进程探针；classpath 刻意排除 agent，确保增强代码只能从 Boot-Class-Path 加载。 */
  public static final class Probe {

    private Probe() {}

    public static void main(String[] args) throws Exception {
      ExecutorService worker = Executors.newSingleThreadExecutor();
      try {
        worker.submit(() -> MDC.get("traceId")).get(5, TimeUnit.SECONDS);
        MDC.put("traceId", "origin-trace");
        String propagated = worker.submit(() -> MDC.get("traceId")).get(5, TimeUnit.SECONDS);
        if (!"origin-trace".equals(propagated)) {
          throw new AssertionError("traceId did not propagate into a prestarted worker");
        }
        MDC.clear();
        if (worker.submit(() -> MDC.get("traceId")).get(5, TimeUnit.SECONDS) != null) {
          throw new AssertionError("traceId leaked into the next task");
        }
      } finally {
        worker.shutdownNow();
      }
    }
  }
}
