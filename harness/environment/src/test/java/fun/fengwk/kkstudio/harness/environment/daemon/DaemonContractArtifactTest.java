package fun.fengwk.kkstudio.harness.environment.daemon;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Daemon 协议常量产物的漂移门禁：由 Java 协议常量重新生成，必须与提交的 TypeScript 逐字符一致。
 *
 * <p>更新必须显式执行 {@code scripts/dev/verify/generated-contracts/write.sh}（等价于带 {@code
 * -Dkkstudio.contract.generation.update=true} 运行本测试），并人工审阅 diff。
 */
class DaemonContractArtifactTest {

  /** 生成产物相对仓库根的位置。 */
  static final String ARTIFACT_RELATIVE_PATH = "daemon/src/generated/protocol-contract.ts";

  private static final boolean UPDATE = Boolean.getBoolean("kkstudio.contract.generation.update");

  /** 测试意图：提交的产物与 Java 协议常量重新生成的字节完全一致（含 LF 换行）。 */
  @Test
  void committedArtifactMatchesTheJavaProtocol() {
    String generated = DaemonContractGenerator.generate();
    Path artifact = repositoryRoot().resolve(ARTIFACT_RELATIVE_PATH);
    if (UPDATE) {
      try {
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, generated, UTF_8);
      } catch (IOException ex) {
        throw new UncheckedIOException("failed to write " + artifact, ex);
      }
    }
    assertArtifactMatches(artifact, generated);
  }

  /** 测试意图：两次生成逐字节一致，不含绝对路径；协议常量真实进入产物。 */
  @Test
  void generationIsDeterministicAndCarriesTheProtocolFacts() {
    String first = DaemonContractGenerator.generate();
    assertEquals(first, DaemonContractGenerator.generate());
    assertFalse(first.contains("\r"));
    assertFalse(first.contains(repositoryRoot().toString()));
    assertTrue(first.contains("export const PROTOCOL_VERSION = " + DaemonProtocol.VERSION + "\n"));
    assertTrue(first.contains("  'IDE_RESULT',\n"));
    assertTrue(first.contains("  'macos',\n"));
  }

  /** 测试意图：产物被手改或删除后门禁必须确定失败。 */
  @Test
  void driftGateRejectsTamperedAndMissingArtifacts() throws IOException {
    String generated = DaemonContractGenerator.generate();
    Path directory = Files.createTempDirectory("kkstudio-daemon-contract");
    Path artifact = directory.resolve("protocol-contract.ts");
    Files.writeString(artifact, generated.replace("= 4\n", "= 5\n"), UTF_8);

    AssertionError drifted =
        assertThrows(AssertionError.class, () -> assertArtifactMatches(artifact, generated));
    assertTrue(drifted.getMessage().contains("drifted"), drifted.getMessage());
    AssertionError missing =
        assertThrows(
            AssertionError.class,
            () -> assertArtifactMatches(directory.resolve("missing.ts"), generated));
    assertTrue(missing.getMessage().contains("missing"), missing.getMessage());
  }

  /** 逐行比对，失败时只报告首个差异行。 */
  private static void assertArtifactMatches(Path artifact, String generated) {
    if (!Files.exists(artifact)) {
      throw new AssertionError(
          "generated daemon contract is missing; regenerate with"
              + " scripts/dev/verify/generated-contracts/write.sh: "
              + ARTIFACT_RELATIVE_PATH);
    }
    String actual;
    try {
      actual = Files.readString(artifact, UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("failed to read " + artifact, ex);
    }
    if (generated.equals(actual)) {
      return;
    }
    List<String> expectedLines = generated.lines().toList();
    List<String> actualLines = actual.lines().toList();
    for (int i = 0; i < Math.max(expectedLines.size(), actualLines.size()); i++) {
      String left = i < expectedLines.size() ? expectedLines.get(i) : "<missing line>";
      String right = i < actualLines.size() ? actualLines.get(i) : "<missing line>";
      if (!left.equals(right)) {
        throw new AssertionError(
            "daemon contract drifted at line "
                + (i + 1)
                + "; regenerate with scripts/dev/verify/generated-contracts/write.sh\n  expected: "
                + left
                + "\n  actual:   "
                + right);
      }
    }
    throw new AssertionError("daemon contract drifted in trailing whitespace or bytes");
  }

  /** 从工作目录逐级上溯定位仓库根：同时含 daemon/package.json 与根 pom.xml 的目录。 */
  private static Path repositoryRoot() {
    for (Path candidate = Path.of("").toAbsolutePath();
        candidate != null;
        candidate = candidate.getParent()) {
      if (Files.exists(candidate.resolve("daemon/package.json"))
          && Files.exists(candidate.resolve("pom.xml"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate the repository root");
  }
}
