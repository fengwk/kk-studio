package fun.fengwk.kkstudio.harness.daemon.coding;

import org.eclipse.jgit.ignore.FastIgnoreRule;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 检索目标共享的 Git 忽略解析：从目标自身向上发现祖先 {@code .gitignore} 与 {@code .git/info/exclude}。
 *
 * <p>忽略行为只由目标路径决定，与调用方的 workdir 无关：规则基线是目标的真实路径祖先链，而不是某次调用的工作目录。最近的、包含 {@code .git} 目录或 {@code
 * .git} 文件（linked worktree）的祖先被视为仓库根；仓库根以上的 {@code .gitignore} 按 git 语义不生效，仓库根及其后代 {@code
 * .gitignore} 从外到内、文件内从上到下求值并采用 last-match-wins。仓库根的 {@code info/exclude} 优先级低于同目录 {@code
 * .gitignore}，与 git 一致。被忽略的目录在加载其内部规则前就会被剪枝，因此后代规则不能错误地重新包含已忽略目录。
 *
 * <p>没有 {@code .git} 的普通目录同样按祖先链解析 {@code .gitignore}，从而不会因为 workdir 恰好落在子目录而漏掉上级规则，也不会因 workdir
 * 变化改变同一目标的忽略结果。不引入任何 Environment 根目录概念。
 *
 * <p>单条 pattern 的解析与匹配由 {@link FastIgnoreRule} 承担（JGit gitignore 语义）。真实目录遍历逐节点评估，使用 {@code
 * isMatch(relativePath, directory, true)}：basename 只用末段匹配，避免 {@code !foo} 错误重包含 {@code
 * foo/bar.log}；目录的忽略判定在父遍历中先于其后代完成。
 *
 * <p>忽略元数据本身不可读（不存在、无权限等）时按“没有该文件”降级，不影响内容扫描；真正的内容读取失败由搜索调用方显式上报。
 */
final class GitIgnoreRules {

  private final List<Rule> rules;

  private GitIgnoreRules(List<Rule> rules) {
    this.rules = rules;
  }

  /**
   * 从 {@code searchDirectory} 向上构建忽略上下文，并判断检索起点自身是否已被祖先规则忽略。
   *
   * <p>返回的规则包含检索起点自己的 {@code .gitignore}（作用于其后代），因此调用方可以直接开始遍历。
   */
  static Prepared prepare(Path searchDirectory, SearchControl control) throws InterruptedException {
    Path target = searchDirectory.toAbsolutePath().normalize();
    List<Path> chain = ancestorChain(target);
    int repositoryIndex = repositoryRootIndex(chain);
    int start = repositoryIndex >= 0 ? repositoryIndex : 0;

    GitIgnoreRules context = new GitIgnoreRules(List.of());
    if (repositoryIndex >= 0) {
      context = context.withExcludeRules(chain.get(repositoryIndex), control);
    }
    for (int index = start; index < chain.size(); index++) {
      control.check();
      Path directory = chain.get(index);
      if (index > start && context.isIgnored(directory, true, control)) {
        return new Prepared(context, true);
      }
      context = context.withDirectoryRules(directory, control);
    }
    return new Prepared(context, false);
  }

  /** 追加 {@code directory/.gitignore} 中、以该目录为基线的规则。 */
  GitIgnoreRules withDirectoryRules(Path directory, SearchControl control)
      throws InterruptedException {
    return appendRules(directory.resolve(".gitignore"), directory, control);
  }

  /**
   * 追加仓库根 {@code $GIT_DIR/info/exclude} 的规则，基线为仓库根（{@code info/exclude} 与仓库根 {@code .gitignore}
   * 同层，但优先级更低）。
   *
   * <p>{@code $GIT_DIR} 由 {@code .git} 目录或 {@code .git} 文件解析：文件形式为 linked worktree，其 {@code
   * info/exclude} 位于 {@code commondir} 指向的公共 git 目录。
   */
  GitIgnoreRules withExcludeRules(Path repositoryRoot, SearchControl control)
      throws InterruptedException {
    Path excludeFile = resolveExcludeFile(repositoryRoot);
    return excludeFile == null ? this : appendRules(excludeFile, repositoryRoot, control);
  }

  boolean isIgnored(Path path, boolean directory, SearchControl control)
      throws InterruptedException {
    if (SearchFiles.isGitMetadata(path)) {
      return true;
    }
    boolean ignored = false;
    for (Rule rule : rules) {
      control.check();
      if (rule.matches(path, directory)) {
        ignored = rule.rule().getResult();
      }
    }
    return ignored;
  }

  private GitIgnoreRules appendRules(Path ruleFile, Path baseDirectory, SearchControl control)
      throws InterruptedException {
    if (!Files.isRegularFile(ruleFile, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(ruleFile)) {
      return this;
    }
    List<Rule> additions = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(ruleFile, StandardCharsets.UTF_8)) {
      while (true) {
        control.check();
        String line = reader.readLine();
        if (line == null) {
          break;
        }
        Rule rule = Rule.parse(baseDirectory, line);
        if (rule != null) {
          additions.add(rule);
        }
      }
    } catch (IOException | SecurityException ignored) {
      return this;
    }
    if (additions.isEmpty()) {
      return this;
    }
    List<Rule> combined = new ArrayList<>(rules.size() + additions.size());
    combined.addAll(rules);
    combined.addAll(additions);
    return new GitIgnoreRules(List.copyOf(combined));
  }

  /** 目标的绝对祖先链：最外层（文件系统根）在前，目标自身在后。 */
  private static List<Path> ancestorChain(Path target) {
    List<Path> chain = new ArrayList<>();
    for (Path current = target; current != null; current = current.getParent()) {
      chain.add(current);
    }
    Collections.reverse(chain);
    return chain;
  }

  /** 最近的、包含 {@code .git}（目录或文件）的祖先下标；没有则返回 -1。 */
  private static int repositoryRootIndex(List<Path> chain) {
    for (int index = chain.size() - 1; index >= 0; index--) {
      if (Files.exists(chain.get(index).resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
        return index;
      }
    }
    return -1;
  }

  /** 解析 {@code info/exclude} 位置：优先公共 git 目录（linked worktree），回退到 worktree 自身 git 目录。 */
  private static Path resolveExcludeFile(Path repositoryRoot) {
    Path dotGit = repositoryRoot.resolve(".git");
    Path gitDir;
    if (Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS)) {
      gitDir = dotGit;
    } else if (Files.isRegularFile(dotGit, LinkOption.NOFOLLOW_LINKS)) {
      gitDir = readGitDirPointer(dotGit, repositoryRoot);
      if (gitDir == null) {
        return null;
      }
    } else {
      return null;
    }
    Path commonDir = readCommonDir(gitDir);
    Path commonExclude = excludeFile(commonDir);
    if (commonExclude != null) {
      return commonExclude;
    }
    return excludeFile(gitDir);
  }

  private static Path excludeFile(Path gitDir) {
    if (gitDir == null) {
      return null;
    }
    Path exclude = gitDir.resolve("info").resolve("exclude");
    return Files.isRegularFile(exclude, LinkOption.NOFOLLOW_LINKS) ? exclude : null;
  }

  /** 解析 {@code .git} 文件里的 {@code gitdir:} 指针；相对路径以仓库根为基准。 */
  private static Path readGitDirPointer(Path dotGitFile, Path repositoryRoot) {
    try (BufferedReader reader = Files.newBufferedReader(dotGitFile, StandardCharsets.UTF_8)) {
      String line = reader.readLine();
      if (line == null || !line.startsWith("gitdir:")) {
        return null;
      }
      String value = line.substring("gitdir:".length()).trim();
      if (value.isEmpty()) {
        return null;
      }
      Path gitDir = Path.of(value);
      return (gitDir.isAbsolute() ? gitDir : repositoryRoot.resolve(gitDir)).normalize();
    } catch (IOException | RuntimeException error) {
      return null;
    }
  }

  /** 解析 linked worktree 的 {@code commondir}；相对路径以 worktree git 目录为基准。 */
  private static Path readCommonDir(Path gitDir) {
    Path commonFile = gitDir.resolve("commondir");
    if (!Files.isRegularFile(commonFile, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    try (BufferedReader reader = Files.newBufferedReader(commonFile, StandardCharsets.UTF_8)) {
      String line = reader.readLine();
      if (line == null || line.isBlank()) {
        return null;
      }
      Path common = Path.of(line.trim());
      return (common.isAbsolute() ? common : gitDir.resolve(common)).normalize();
    } catch (IOException | RuntimeException error) {
      return null;
    }
  }

  record Prepared(GitIgnoreRules rules, boolean searchDirectoryIgnored) {}

  private record Rule(Path baseDirectory, FastIgnoreRule rule) {

    private static Rule parse(Path baseDirectory, String source) {
      FastIgnoreRule rule = new FastIgnoreRule(source);
      if (rule.isEmpty()) {
        // 空行、comment（#）或无效 pattern：git 语义下不产生任何规则。
        return null;
      }
      return new Rule(baseDirectory, rule);
    }

    private boolean matches(Path path, boolean directory) {
      if (!path.startsWith(baseDirectory) || path.equals(baseDirectory)) {
        return false;
      }
      String relative = SearchFiles.toPosix(baseDirectory.relativize(path));
      return rule.isMatch(relative, directory, true);
    }
  }
}
