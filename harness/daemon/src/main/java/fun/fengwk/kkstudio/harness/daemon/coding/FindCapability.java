package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 使用 Java NIO 查找遵守分层 {@code .gitignore}（见 {@link GitIgnoreRules}）的 environment 文件。
 *
 * <p>忽略规则只由检索起点决定，与调用 workdir 无关；无法读取的节点必须显式上报，不能默默等同于“没有匹配文件”。
 *
 * <p>{@code workdir} 可选：省略时 {@code path} 必须是绝对路径，结果按绝对路径展示，绝不推断 cwd、HOME
 * 或其它默认目录；提供时必须仍是现存可读的绝对目录，结果保持相对 workdir 的形态。
 */
public final class FindCapability extends AbstractCodingCapability {

  /** 未搜索路径在结果摘要中最多列出的条目数。 */
  private static final int MAX_SKIP_DETAIL = 10;

  public FindCapability(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_FIND));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String rawWorkdir = optionalString(args, "workdir");
    Path workdir = rawWorkdir == null ? null : EnvironmentPaths.workdir(rawWorkdir);
    Path path = EnvironmentPaths.existing(string(args, "path"), workdir);
    int limit = optionalPositiveInt(args, "limit", 1000, 100_000);
    // 有效超时在 Platform 侧解析完成（definition 默认值或显式 timeout_seconds）；这里只消费它。
    Duration timeout = request.timeout();
    SearchControl control = SearchControl.start(timeout, execution, "find");
    String sourcePattern = string(args, "pattern");
    boolean pathPattern = sourcePattern.indexOf('/') >= 0;
    control.check();
    GlobPattern pattern = GlobPattern.compile(sourcePattern);
    control.check();

    List<String> matchedPaths = new ArrayList<>();
    Map<String, String> skipped = new LinkedHashMap<>();
    SearchFiles.Report report =
        SearchFiles.walk(
            path,
            control,
            file -> {
              control.check();
              String searchRelative = SearchFiles.toPosix(path.relativize(file));
              String basename = file.getFileName().toString();
              if (pattern.matches(pathPattern ? searchRelative : basename)) {
                matchedPaths.add(displayPath(workdir, file));
                if (matchedPaths.size() > limit) {
                  return false;
                }
              }
              return true;
            });
    for (Path unreadable : report.unreadable()) {
      skipped.put(displayPath(workdir, unreadable), "could not be read");
    }

    if (matchedPaths.isEmpty()) {
      if (!skipped.isEmpty()) {
        return error(
            request.call().id(),
            "No files found matching pattern, but "
                + skipped.size()
                + " path(s) could not be searched: ["
                + describeSkipped(skipped)
                + "]");
      }
      return success(request.call().id(), "No files found matching pattern");
    }

    boolean limitReached = matchedPaths.size() > limit;
    int displayCount = Math.min(limit, matchedPaths.size());

    try (OutputSpool spool = new OutputSpool(config.textOutputStore(), request.call().id())) {
      for (int index = 0; index < displayCount; index++) {
        if (index > 0) {
          spool.write((int) '\n');
        }
        spool.write(matchedPaths.get(index).getBytes(StandardCharsets.UTF_8));
      }
      if (limitReached) {
        spool.write(
            ("\n\n[" + limit + " results limit reached. Refine the pattern or raise limit.]")
                .getBytes(StandardCharsets.UTF_8));
      }
      if (!skipped.isEmpty()) {
        spool.write(
            ("\n\n["
                    + skipped.size()
                    + " path(s) could not be searched: "
                    + describeSkipped(skipped)
                    + "]")
                .getBytes(StandardCharsets.UTF_8));
      }
      return spool.finish(false);
    }
  }

  /**
   * 展示路径：调用方给了 workdir 时保持相对 workdir 的展示（越界目标仍是 {@code ../} 形态）；没有 workdir，或目标与 workdir 跨根（Windows
   * 上不同驱动器） 无法相对化时退化为目标的绝对路径。任何情况下都不回退到 cwd、HOME 或其它默认目录。
   */
  private static String displayPath(Path workdir, Path file) {
    if (workdir == null) {
      return SearchFiles.toPosix(file);
    }
    try {
      return SearchFiles.toPosix(workdir.relativize(file));
    } catch (IllegalArgumentException differentRoots) {
      return SearchFiles.toPosix(file);
    }
  }

  private static String describeSkipped(Map<String, String> skipped) {
    StringBuilder builder = new StringBuilder();
    int shown = 0;
    for (Map.Entry<String, String> entry : skipped.entrySet()) {
      if (shown == MAX_SKIP_DETAIL) {
        builder.append(", ...");
        break;
      }
      if (shown > 0) {
        builder.append(", ");
      }
      builder.append(entry.getKey()).append(" (").append(entry.getValue()).append(')');
      shown++;
    }
    return builder.toString();
  }
}
