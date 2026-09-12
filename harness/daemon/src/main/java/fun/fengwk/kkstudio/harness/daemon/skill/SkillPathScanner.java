package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDiagnostic;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 单个 Skill 根目录的 PATH 扫描。
 *
 * <p>扫描规则：根目录自身含 {@code SKILL.md} 时识别为一个 skill 并停止下探；否则递归子目录，遇到 skill 根即停止该分支下探。跳过 {@code .git}
 * 与常见依赖/缓存目录；符号链接可以越界，但用 canonical/real path visited set 防止环。坏 entry（含超限元数据、非法 UTF-8、不可读目录）只形成有界诊断，
 * 不终止扫描，也不终止 Daemon；发现结果按 baseDirectory 稳定排序，保证同一文件系统状态的输出可复现。
 *
 * <p>目录遍历没有数量配额：任何合法目录都必须被访问，否则“看起来成功”的发布会让 skill 静默缺失。循环与重复只能由 canonical visited set 阻止，诊断输出由
 * {@link #MAX_DIAGNOSTICS} 单独限界。
 *
 * <p>同名冲突不是本类职责：扫描只报告“发现了哪些 skill、哪里有问题”，跨来源的唯一性由 registry 在发布前统一校验。
 */
final class SkillPathScanner {

  /** 永远不进入的目录名。 */
  private static final Set<String> SKIPPED_DIRECTORIES =
      Set.of(
          ".git",
          "node_modules",
          "vendor",
          "target",
          "build",
          "dist",
          ".cache",
          ".venv",
          "__pycache__");

  /** 单次扫描的诊断条数上限。 */
  static final int MAX_DIAGNOSTICS = 256;

  private final List<DaemonSkill> skills = new ArrayList<>();
  private final List<DaemonSkillDiagnostic> diagnostics = new ArrayList<>();
  private final Set<Path> visited = new HashSet<>();

  private SkillPathScanner() {}

  /** 扫描一个已确认存在的 skill 根目录。 */
  static ScanResult scan(Path root) {
    Objects.requireNonNull(root, "root");
    SkillPathScanner scanner = new SkillPathScanner();
    scanner.walk(root);
    List<DaemonSkill> ordered =
        scanner.skills.stream().sorted(SkillPathScanner::byBaseDirectory).toList();
    List<DaemonSkillDiagnostic> orderedDiagnostics = List.copyOf(scanner.diagnostics);
    return new ScanResult(ordered, orderedDiagnostics);
  }

  private static int byBaseDirectory(DaemonSkill left, DaemonSkill right) {
    return left.baseDirectory().toString().compareTo(right.baseDirectory().toString());
  }

  /** {@code directory} 是已 real-path 化的目录；visited set 按真实路径判定，因此 symlink 环与重复入口都收敛。 */
  private void walk(Path directory) {
    // 递归扫描期间持续检查中断：被取消的扫描必须在有界时间内停止，而不是遍历完整棵目录树。
    DaemonSkillException.requireNotInterrupted();
    Path canonical = canonical(directory);
    if (canonical == null || !visited.add(canonical)) {
      return;
    }
    if (Files.isRegularFile(canonical.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) {
      register(canonical);
      return;
    }
    List<Path> children;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(canonical)) {
      children = new ArrayList<>();
      for (Path child : stream) {
        children.add(child);
      }
    } catch (IOException error) {
      diagnostic(canonical, "directory is not listable");
      return;
    }
    children.sort(Path::compareTo);
    for (Path child : children) {
      if (!Files.isDirectory(child)) {
        continue;
      }
      if (SKIPPED_DIRECTORIES.contains(child.getFileName().toString())) {
        continue;
      }
      walk(child);
    }
  }

  private void register(Path skillDirectory) {
    DaemonSkill skill;
    try {
      skill = SkillFrontMatterParser.parse(skillDirectory, "path scan");
    } catch (SkillParseException error) {
      diagnostic(skillDirectory, error.reason());
      return;
    } catch (RuntimeException error) {
      diagnostic(skillDirectory, "SKILL.md cannot be parsed");
      return;
    }
    try {
      // 完整 descriptor 契约在此复核：违反者只是坏 skill，绝不中止整个来源的发布。
      DaemonSkillDescriptor.canonicalName(skill.name());
      DaemonSkillDescriptor.canonicalDescription(skill.description());
      DaemonSkillDescriptor.canonicalBaseDirectory(skill.baseDirectory().toString());
      // 加载结果的 JSON 转义后仍必须落在通用 JsonResultContent 上限内，否则不能进入 READY。
      SkillLoadPayload.encode(skill.body(), skill.baseDirectory().toString());
    } catch (IllegalArgumentException error) {
      diagnostic(skillDirectory, "skill cannot be represented by the load protocol");
      return;
    }
    skills.add(skill);
  }

  private void diagnostic(Path location, String message) {
    if (diagnostics.size() >= MAX_DIAGNOSTICS) {
      return;
    }
    diagnostics.add(new DaemonSkillDiagnostic(location.toString(), message));
  }

  /** 返回目录的真实路径；符号链接环或不可解析路径返回 {@code null}，由调用方决定诊断。 */
  private static Path canonical(Path directory) {
    try {
      return directory.toRealPath();
    } catch (IOException error) {
      return null;
    }
  }

  /** 一次 PATH 扫描的稳定结果。 */
  record ScanResult(List<DaemonSkill> skills, List<DaemonSkillDiagnostic> diagnostics) {}
}
