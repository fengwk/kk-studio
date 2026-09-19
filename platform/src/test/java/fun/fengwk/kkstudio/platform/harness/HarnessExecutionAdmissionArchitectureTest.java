package fun.fengwk.kkstudio.platform.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 锁定阻塞执行的容量与准入边界：
 *
 * <ul>
 *   <li>Model/Tool 网关使用底层无界虚拟线程，但由显式 ConcurrencyAdmission 提供运行时准入限制；
 *   <li>Subagent 执行使用固定容量零队列 executor，受部署级并发配置严格约束。
 * </ul>
 */
class HarnessExecutionAdmissionArchitectureTest {

  @Test
  void productionBlockingExecutorsHaveExplicitCapacityBoundaries() throws IOException {
    Path root = repositoryRoot();
    String model =
        Files.readString(
            root.resolve(
                "platform/src/main/java/fun/fengwk/kkstudio/platform/harness/model/ModelExecutionConfiguration.java"));
    String tool =
        Files.readString(
            root.resolve(
                "platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/gateway/HarnessToolGatewayConfiguration.java"));
    String runtime =
        Files.readString(
            root.resolve(
                "platform/src/main/java/fun/fengwk/kkstudio/platform/harness/tool/BuiltinHarnessContributorConfiguration.java"));

    assertTrue(
        model.contains("ConcurrencyAdmission"), "Model executor must have explicit admission");
    assertTrue(model.contains("modelExecutionAdmission"), "Model admission bean must be wired");
    assertTrue(tool.contains("ConcurrencyAdmission"), "Tool executor must have explicit admission");
    assertTrue(tool.contains("toolExecutionAdmission"), "Tool admission bean must be wired");
    assertTrue(runtime.contains("new ThreadPoolExecutor"), "Subagent must use a fixed executor");
    assertTrue(runtime.contains("new SynchronousQueue"), "Subagent executor must have zero queue");
    assertTrue(
        runtime.contains("properties.getSubagent()"), "Subagent capacity must be deployment-bound");
    assertTrue(runtime.contains("new ThreadPoolExecutor.AbortPolicy"));
  }

  @Test
  void unboundedPerTaskExecutorsAreOwnedOnlyByModelAndToolGateways() throws IOException {
    Path platformMain = repositoryRoot().resolve("platform/src/main/java");
    List<String> owners =
        List.of("ModelExecutionConfiguration.java", "HarnessToolGatewayConfiguration.java");
    try (Stream<Path> paths = Files.walk(platformMain)) {
      paths
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  String source = Files.readString(path);
                  if (source.contains("newVirtualThreadPerTaskExecutor")
                      || source.contains("newThreadPerTaskExecutor")) {
                    assertTrue(
                        owners.contains(path.getFileName().toString()),
                        () -> "unbounded per-task executor bypasses admission: " + path);
                  }
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
  }

  private static Path repositoryRoot() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate : List.of(cwd, cwd.getParent())) {
      if (Files.isDirectory(candidate.resolve("platform/src/main/java"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate repository root from " + cwd);
  }
}
