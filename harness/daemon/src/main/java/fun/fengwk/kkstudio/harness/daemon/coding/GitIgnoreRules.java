package fun.fengwk.kkstudio.harness.daemon.coding;

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
        Rule rule;
        try {
          rule = Rule.parse(directory, line);
        } catch (IllegalArgumentException ignored) {
          continue;
        }
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
        ignored = !rule.negated();
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

  private record Rule(
      Path baseDirectory,
      boolean negated,
      boolean directoryOnly,
      boolean pathPattern,
      GlobPattern pattern) {

    private static Rule parse(Path baseDirectory, String source) {
      String value = stripTrailingSpaces(source);
      if (value.isEmpty()) {
        return null;
      }
      boolean escapedLeading = value.startsWith("\\#") || value.startsWith("\\!");
      if (escapedLeading) {
        value = value.substring(1);
      } else if (value.startsWith("#")) {
        return null;
      }
      boolean negated = !escapedLeading && value.startsWith("!");
      if (negated) {
        value = value.substring(1);
      }
      if (value.isEmpty()) {
        return null;
      }
      boolean directoryOnly = value.endsWith("/");
      if (directoryOnly) {
        value = value.substring(0, value.length() - 1);
      }
      boolean anchored = value.startsWith("/");
      if (anchored) {
        value = value.substring(1);
      }
      if (value.isEmpty()) {
        return null;
      }
      boolean pathPattern = anchored || value.indexOf('/') >= 0;
      return new Rule(
          baseDirectory, negated, directoryOnly, pathPattern, GlobPattern.compile(value));
    }

    private static String stripTrailingSpaces(String source) {
      String value = source;
      while (value.endsWith(" ")) {
        int slashCount = 0;
        for (int index = value.length() - 2; index >= 0 && value.charAt(index) == '\\'; index--) {
          slashCount++;
        }
        if (slashCount % 2 == 1) {
          int escape = value.length() - slashCount - 1;
          return value.substring(0, escape) + value.substring(escape + 1);
        }
        value = value.substring(0, value.length() - 1);
      }
      return value;
    }

    private boolean matches(Path path, boolean directory) {
      if (!path.startsWith(baseDirectory) || path.equals(baseDirectory)) {
        return false;
      }
      String relative = SearchFiles.toPosix(baseDirectory.relativize(path));
      if (pathPattern) {
        return (!directoryOnly || directory) && pattern.matches(relative);
      }
      Path basename = path.getFileName();
      return basename != null
          && (!directoryOnly || directory)
          && pattern.matches(basename.toString());
    }
  }
}
