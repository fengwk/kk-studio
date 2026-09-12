package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * PATH 来源扫描语义：递归、Skill 根停止下探、依赖目录跳过、symlink 环与坏 Skill 诊断、稳定排序与 path 展开。
 *
 * <p>测试意图：把“发现”与“发布”分开验证 —— 扫描必须容忍坏 entry 与异常目录结构（形成有界诊断），只有扫描结果进入发布时才做 唯一性与持久化校验。
 */
class SkillPathScannerTest {

  @TempDir Path tempDir;

  /** 根目录没有 SKILL.md 时递归子目录；子目录含 SKILL.md 即识别为该 skill 并停止下探。 */
  @Test
  void recursivelyDiscoversNestedSkillRootsAndStopsDescending() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root.resolve("alpha"), "alpha");
    Path beta = writeSkill(root.resolve("group/beta"), "beta");
    // beta 内部再放一个嵌套 SKILL.md：它属于 beta 的内容，不是独立 skill。
    writeSkill(beta.resolve("nested"), "nested");

    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root));

    assertEquals(List.of("alpha", "beta"), skillNames(snapshot));
  }

  /** 根目录自身含 SKILL.md 时整个根就是一个 skill，绝不下探。 */
  @Test
  void treatsRootSkillMdAsSingleSkill() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root, "root-skill");
    writeSkill(root.resolve("child"), "child-skill");

    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root));

    assertEquals(List.of("root-skill"), skillNames(snapshot));
  }

  /** 依赖/缓存目录被跳过：其中的 SKILL.md 不参与发现。 */
  @Test
  void skipsDependencyAndCacheDirectories() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root.resolve("alpha"), "alpha");
    for (String skipped :
        List.of(
            ".git",
            "node_modules",
            "vendor",
            "target",
            "build",
            "dist",
            ".cache",
            ".venv",
            "__pycache__")) {
      writeSkill(root.resolve(skipped).resolve("ignored-" + skipped.replace('.', 'x')), "ignored");
    }

    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root));

    assertEquals(List.of("alpha"), skillNames(snapshot));
  }

  /** symlink 环必须靠 real path visited set 收敛：扫描终止且只发现真实 skill。 */
  @Test
  void terminatesOnSymlinkCycles() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root.resolve("alpha"), "alpha");
    Path loop = Files.createDirectories(root.resolve("loop"));
    try {
      Files.createSymbolicLink(loop.resolve("back"), root);
    } catch (UnsupportedOperationException | IOException error) {
      return;
    }
    // 同一真实目录的第二个入口同样不能重复发现。
    try {
      Files.createSymbolicLink(root.resolve("alias"), root);
    } catch (UnsupportedOperationException | IOException ignored) {
      // 平台不支持符号链接时只验证环本身。
    }

    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root));

    assertEquals(List.of("alpha"), skillNames(snapshot));
  }

  /** 一个坏 Skill 只形成有界诊断，其余健康 skill 仍被发布，Daemon 不因坏 entry 失败。 */
  @Test
  void reportsBadSkillsAsDiagnosticsAndKeepsHealthyOnes() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root.resolve("healthy"), "healthy");
    Path missingDescription = Files.createDirectories(root.resolve("missing-description"));
    Files.writeString(
        missingDescription.resolve("SKILL.md"), "---\nname: missing-description\n---\n");
    Path notUtf8 = Files.createDirectories(root.resolve("not-utf8"));
    Files.write(notUtf8.resolve("SKILL.md"), new byte[] {(byte) 0xFF, (byte) 0xFE});

    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root));

    assertEquals(List.of("healthy"), skillNames(snapshot));
    assertEquals(2, snapshot.diagnostics().size());
    // 诊断是位置 + 结构性原因，绝不复述 SKILL.md 内容。
    for (var diagnostic : snapshot.diagnostics()) {
      assertTrue(diagnostic.location().startsWith(root.toRealPath().toString()));
      assertFalse(diagnostic.message().contains("---"));
    }
  }

  /** name 中的换行与 description 中的控制字符违反 READY descriptor 契约，只能形成单 Skill 诊断。 */
  @Test
  void invalidDescriptorMetadataBecomesDiagnostics() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root.resolve("healthy"), "healthy");
    Path multilineName = Files.createDirectories(root.resolve("multiline-name"));
    Files.writeString(
        multilineName.resolve("SKILL.md"),
        "---\nname: |\n  bad\n  name\ndescription: Invalid name\n---\n# body\n");
    Path controlledDescription = Files.createDirectories(root.resolve("controlled-description"));
    Files.writeString(
        controlledDescription.resolve("SKILL.md"),
        "---\nname: bad-description\ndescription: \"bad\\tdescription\"\n---\n# body\n");

    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root));

    assertEquals(List.of("healthy"), skillNames(snapshot));
    assertEquals(2, snapshot.diagnostics().size());
    assertTrue(
        snapshot.diagnostics().stream()
            .allMatch(
                diagnostic ->
                    diagnostic
                        .message()
                        .equals("skill cannot be represented by the load protocol")));
  }

  /**
   * 超限元数据只是单个坏 Skill：它必须变成有界诊断，健康 skill 仍被发布。
   *
   * <p>超长 name/description 违反 descriptor 契约；若在解析期之外的边界失败，就会把整个来源的发布一并中止，因此这里直接断言发布仍成功。
   */
  @Test
  void oversizedMetadataBecomesADiagnosticAlongsideAHealthySkill() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root.resolve("healthy"), "healthy");
    Path oversizedName = Files.createDirectories(root.resolve("long-name"));
    Files.writeString(
        oversizedName.resolve("SKILL.md"),
        "---\nname: "
            + "n".repeat(DaemonSkillDescriptor.MAX_NAME_CHARS + 1)
            + "\ndescription: too long name\n---\n# body\n");
    Path oversizedDescription = Files.createDirectories(root.resolve("long-description"));
    Files.writeString(
        oversizedDescription.resolve("SKILL.md"),
        "---\nname: long-description\ndescription: "
            + "d".repeat(DaemonSkillDescriptor.MAX_DESCRIPTION_CHARS + 1)
            + "\n---\n# body\n");

    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root));

    assertEquals(List.of("healthy"), skillNames(snapshot));
    assertEquals(2, snapshot.diagnostics().size());
    for (var diagnostic : snapshot.diagnostics()) {
      assertTrue(
          diagnostic.message().contains("exceeds the supported length"), diagnostic.message());
      // 诊断只描述结构性原因，绝不复述 front matter 内容。
      assertFalse(diagnostic.message().contains("nnnn"), diagnostic.message());
      assertFalse(diagnostic.message().contains("dddd"), diagnostic.message());
    }
  }

  /** JSON 转义后超出通用结果上限的正文不能进入 READY，否则该 Skill 会被广告却永远无法加载。 */
  @Test
  void rejectsSkillBodyThatCannotFitTheLoadResultProtocol() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    Path oversized = Files.createDirectories(root.resolve("oversized"));
    Files.writeString(
        oversized.resolve("SKILL.md"),
        "---\nname: oversized\ndescription: Oversized body\n---\n" + "\"".repeat(600_000));
    writeSkill(root.resolve("healthy"), "healthy");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), tempDir);

    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root));

    assertEquals(List.of("healthy"), skillNames(snapshot));
    assertEquals(1, snapshot.diagnostics().size());
    assertEquals(
        "skill cannot be represented by the load protocol",
        snapshot.diagnostics().get(0).message());
  }

  /**
   * 目录遍历没有数量配额：大量目录不会让发现结果静默缺失。
   *
   * <p>人工 visited-directory 上限会让"看起来成功"的发布漏掉后面的 skill，因此这里用超过任何历史配额的量级断言全部被发现。
   */
  @Test
  void visitsEveryDirectoryWithoutAnArtificialQuota() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    for (int index = 0; index < 3_000; index++) {
      Files.createDirectories(root.resolve("empty-" + index));
    }
    writeSkill(root.resolve("zeta"), "zeta");

    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root));

    assertEquals(List.of("zeta"), skillNames(snapshot));
  }

  /** 发现结果按 baseDirectory 稳定排序：同一文件系统状态的输出可复现。 */
  @Test
  void ordersDiscoveriesByBaseDirectory() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root.resolve("zeta"), "zeta");
    writeSkill(root.resolve("alpha"), "alpha");
    writeSkill(root.resolve("group/middle"), "middle");

    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root));

    List<String> directories =
        snapshot.skills().stream().map(skill -> skill.baseDirectory()).toList();
    List<String> sorted = directories.stream().sorted().toList();
    assertEquals(sorted, directories);
    assertEquals(List.of("alpha", "middle", "zeta"), skillNames(snapshot));
  }

  /** {@code ~/} 前缀按 Daemon 用户 HOME 展开，绝不使用进程 cwd。 */
  @Test
  void expandsHomePrefixAgainstDaemonUserHome() throws IOException {
    Path home = Files.createDirectories(tempDir.resolve("home"));
    writeSkill(home.resolve("skills/dev"), "dev");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(tempDir.resolve("data"), home);

    DaemonSkillSourceSnapshot snapshot =
        registry.refresh(
            DaemonSkillSourceConfig.path(
                DaemonSkillTestSupport.SOURCE_ID,
                1,
                1,
                "~/skills",
                false,
                Set.of(DaemonSkillTestSupport.SOURCE_ID)));

    assertEquals(List.of("dev"), skillNames(snapshot));
  }

  /** 绝对路径与 {@code ~/} 以外的形状必须显式失败，避免把相对路径解释成 cwd 下的目录。 */
  @Test
  void rejectsNonAbsoluteSourcePaths() {
    for (String invalid : List.of("relative/skills", "./skills", "~", "~other/skills")) {
      assertThrows(
          DaemonSkillException.class,
          () -> DaemonSkillSourcePaths.resolve(invalid, tempDir),
          "path must be rejected: " + invalid);
    }
  }

  /** {@code ~/} 展开严格基于 Daemon 用户 HOME，而不是进程 cwd 或环境变量。 */
  @Test
  void resolvesHomePrefixedPathsAgainstDaemonUserHome() {
    Path home = tempDir.resolve("home");

    assertEquals(home.resolve("skills/dev"), DaemonSkillSourcePaths.resolve("~/skills/dev", home));
  }

  private static List<String> skillNames(DaemonSkillSourceSnapshot snapshot) {
    return snapshot.skills().stream().map(skill -> skill.name()).toList();
  }

  private DaemonSkillSourceConfig config(Path root) {
    return DaemonSkillSourceConfig.path(
        DaemonSkillTestSupport.SOURCE_ID,
        1,
        1,
        root.toString(),
        false,
        Set.of(DaemonSkillTestSupport.SOURCE_ID));
  }

  private static Path writeSkill(Path directory, String name) throws IOException {
    Files.createDirectories(directory);
    Files.writeString(
        directory.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + name + " description\n---\n# " + name + "\n");
    return directory;
  }
}
