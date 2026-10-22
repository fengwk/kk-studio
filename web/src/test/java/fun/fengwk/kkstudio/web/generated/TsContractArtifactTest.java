package fun.fengwk.kkstudio.web.generated;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.share.canvas.CanvasConflictDTO;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 传输契约生成产物的漂移门禁：扫描 Web 生产控制器、用真实 HTTP Jackson mapper 重新生成，必须与提交的 TypeScript 逐字符一致。
 *
 * <p>缺文件、字段/枚举变化或产物被手改都会失败。更新必须显式执行 {@code scripts/dev/verify/generated-contracts/write.sh}（等价于带
 * {@code -Dkkstudio.contract.generation.update=true} 运行本测试），并人工审阅 diff。
 */
class TsContractArtifactTest {

  /** 生成产物相对仓库根的位置。 */
  static final String ARTIFACT_RELATIVE_PATH = "frontend/src/shared/api/generated/contracts.ts";

  private static final String CONTRACT_PACKAGE = "fun.fengwk.kkstudio.share";

  /** 嵌在错误响应 {@code errors} 上下文里、无法从处理方法签名发现的结构化载荷。 */
  private static final List<Class<?>> ERROR_PAYLOAD_TYPES = List.of(CanvasConflictDTO.class);

  private static final boolean UPDATE = Boolean.getBoolean("kkstudio.contract.generation.update");

  /** 测试意图：提交的生成产物与真实 mapper 重新生成的字节完全一致（含 LF 换行）。 */
  @Test
  void committedArtifactMatchesTheRealJacksonMapper() {
    String generated = generate();
    Path artifact = repositoryRoot().resolve(ARTIFACT_RELATIVE_PATH);

    if (UPDATE) {
      writeArtifact(artifact, generated);
    }

    assertArtifactMatches(artifact, generated);
  }

  /** 测试意图：相同源码两次生成必须逐字节一致，不含日期、绝对路径或机器信息。 */
  @Test
  void generationIsDeterministicAndMachineIndependent() {
    String first = generate();

    assertEquals(first, generate(), "two generations from the same sources must be byte identical");
    assertFalse(first.contains("\r"), "generated artifact must use LF line endings");
    assertFalse(
        first.contains(repositoryRoot().toString()),
        "generated artifact must not embed machine-specific absolute paths");
  }

  /** 测试意图：产物被手改或删除后门禁必须确定失败。 */
  @Test
  void driftGateRejectsTamperedAndMissingArtifacts() throws IOException {
    String generated = generate();
    String tampered = generated.replaceFirst("\n}\n", "\n  tampered: string\n}\n");
    Path directory = Files.createTempDirectory("kkstudio-contract");
    Path artifact = directory.resolve("contracts.ts");
    Files.writeString(artifact, tampered, UTF_8);

    AssertionError drifted =
        assertThrows(AssertionError.class, () -> assertArtifactMatches(artifact, generated));
    assertTrue(drifted.getMessage().contains("drifted"), drifted.getMessage());
    AssertionError missing =
        assertThrows(
            AssertionError.class,
            () -> assertArtifactMatches(directory.resolve("missing.ts"), generated));
    assertTrue(missing.getMessage().contains("missing"), missing.getMessage());
  }

  private static String generate() {
    return new TsContractGenerator(
            StrictHttpJsonMapperTestSupport.get(),
            CONTRACT_PACKAGE,
            controllers(),
            ERROR_PAYLOAD_TYPES)
        .generate();
  }

  /** Web 生产代码中的全部 {@code @RestController}；测试源码里的控制器不进入契约。 */
  static List<Class<?>> controllers() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    List<Class<?>> controllers = new ArrayList<>();
    ClassLoader loader = TsContractArtifactTest.class.getClassLoader();
    for (BeanDefinition candidate : scanner.findCandidateComponents("fun.fengwk.kkstudio.web")) {
      Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(), loader);
      String location = type.getProtectionDomain().getCodeSource().getLocation().getPath();
      if (!location.contains("test-classes")) {
        controllers.add(type);
      }
    }
    controllers.sort(Comparator.comparing(Class::getName));
    return controllers;
  }

  /** 提交产物与重新生成结果一致；缺失或任何差异都以清晰的信息失败。 */
  static void assertArtifactMatches(Path artifact, String generated) {
    if (!Files.exists(artifact)) {
      throw new AssertionError(
          "generated contract artifact is missing; regenerate with"
              + " scripts/dev/verify/generated-contracts/write.sh: "
              + ARTIFACT_RELATIVE_PATH);
    }
    assertSameText(generated, readArtifact(artifact));
  }

  private static String readArtifact(Path artifact) {
    try {
      return Files.readString(artifact, UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read " + artifact, ex);
    }
  }

  private static void writeArtifact(Path artifact, String content) {
    try {
      Files.createDirectories(artifact.getParent());
      Files.writeString(artifact, content, UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to write " + artifact, ex);
    }
  }

  /** 逐行比对并在失败时只报告首个差异行，避免打印整份产物。 */
  static void assertSameText(String expected, String actual) {
    if (expected.equals(actual)) {
      return;
    }
    List<String> expectedLines = expected.lines().toList();
    List<String> actualLines = actual.lines().toList();
    int max = Math.max(expectedLines.size(), actualLines.size());
    for (int i = 0; i < max; i++) {
      String left = i < expectedLines.size() ? expectedLines.get(i) : "<missing line>";
      String right = i < actualLines.size() ? actualLines.get(i) : "<missing line>";
      if (!left.equals(right)) {
        throw new AssertionError(
            "TypeScript contracts drifted at line "
                + (i + 1)
                + "; regenerate with scripts/dev/verify/generated-contracts/write.sh and review the diff\n"
                + "  expected: "
                + left
                + "\n  actual:   "
                + right);
      }
    }
    throw new AssertionError("TypeScript contracts drifted in trailing whitespace or bytes");
  }

  /** 从工作目录逐级上溯定位仓库根：同时含 frontend/package.json 与根 pom.xml 的目录。 */
  static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
      if (Files.exists(candidate.resolve("frontend/package.json"))
          && Files.exists(candidate.resolve("pom.xml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate the kk-studio repository root from " + current);
  }
}
