package fun.fengwk.kkstudio.harness.daemon.coding;

import org.eclipse.jgit.ignore.FastIgnoreRule;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * environment root 内的分层 {@code .gitignore} 规则。
 *
 * <p>规则按祖先到后代、文件内从上到下的顺序求值并采用 last-match-wins。被忽略的目录在加载其内部规则前就会被剪枝，因此后代规则不能错误地重新包含已忽略父目录。
 *
 * <p>单条 pattern 的解析与匹配由 {@link FastIgnoreRule} 承担（JGit gitignore 语义）。真实目录遍历逐节点评估，使用 {@code
 * isMatch(relativePath, directory, true)}：basename 只用末段匹配，避免 {@code !foo} 错误重包含 {@code
 * foo/bar.log}；目录的忽略判定在父遍历中先于其后代完成。
 */
final class GitIgnoreRules {

  private final Path environmentRoot;
  private final List<Rule> rules;

  private GitIgnoreRules(Path environmentRoot, List<Rule> rules) {
    this.environmentRoot = environmentRoot;
    this.rules = rules;
  }

  static Prepared prepare(Path environmentRoot, Path searchDirectory, SearchControl control)
      throws InterruptedException {
    GitIgnoreRules context =
        new GitIgnoreRules(environmentRoot, List.of()).withDirectoryRules(environmentRoot, control);
    Path current = environmentRoot;
    for (Path segment : environmentRoot.relativize(searchDirectory)) {
      control.check();
      current = current.resolve(segment);
      if (isHardExcluded(environmentRoot, current) || context.isIgnored(current, true, control)) {
        return new Prepared(context, true);
      }
      context = context.withDirectoryRules(current, control);
    }
    return new Prepared(context, false);
  }

  GitIgnoreRules withDirectoryRules(Path directory, SearchControl control)
      throws InterruptedException {
    Path ignoreFile = directory.resolve(".gitignore");
    if (!Files.isRegularFile(ignoreFile, LinkOption.NOFOLLOW_LINKS)
        || !Files.isReadable(ignoreFile)) {
      return this;
    }
    List<Rule> additions = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(ignoreFile, StandardCharsets.UTF_8)) {
      while (true) {
        control.check();
        String line = reader.readLine();
        if (line == null) {
          break;
        }
        Rule rule = Rule.parse(directory, line);
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
    return new GitIgnoreRules(environmentRoot, List.copyOf(combined));
  }

  boolean isIgnored(Path path, boolean directory, SearchControl control)
      throws InterruptedException {
    if (isHardExcluded(environmentRoot, path)) {
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

  private static boolean isHardExcluded(Path environmentRoot, Path path) {
    if (!path.startsWith(environmentRoot)) {
      return true;
    }
    for (Path segment : environmentRoot.relativize(path)) {
      if (segment.toString().equals(".git")) {
        return true;
      }
    }
    return false;
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
