package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 使用 Java NIO 查找遵守分层 {@code .gitignore} 的 environment 文件。 */
public final class FindTool extends AbstractCodingTool {

  static final int MAX_TIMEOUT_SECONDS = 3600;

  public FindTool(CodingToolsConfig config) {
    super(config, EnvironmentToolCatalog.require("find"));
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path workdir = boundary.workdir(optionalString(args, "workdir"), request.workdir());
    Path path = boundary.existingWithoutSymlinks(string(args, "path"), workdir);
    int limit = optionalPositiveInt(args, "limit", 1000, 100_000);
    Duration timeout = effectiveSearchTimeout(request.effectiveTimeout(), args);
    SearchControl control = SearchControl.start(timeout, execution, "find");
    String sourcePattern = string(args, "pattern");
    boolean pathPattern = sourcePattern.indexOf('/') >= 0;
    control.check();
    GlobPattern pattern = GlobPattern.compile(sourcePattern);
    control.check();
    List<String> completeLines = new ArrayList<>();
    for (Path file : SearchFiles.collect(config.environmentRoot(), path, control)) {
      control.check();
      String searchRelative = SearchFiles.toPosix(path.relativize(file));
      String basename = file.getFileName().toString();
      if (pattern.matches(pathPattern ? searchRelative : basename)) {
        completeLines.add(SearchFiles.toPosix(workdir.relativize(file)));
      }
    }
    completeLines.sort(Comparator.naturalOrder());
    if (completeLines.isEmpty()) {
      return success(request.call().id(), "No files found matching pattern");
    }
    return result(request.call().id(), completeLines, limit);
  }

  static Duration effectiveSearchTimeout(Duration invocationTimeout, JsonNode args) {
    Objects.requireNonNull(invocationTimeout, "invocationTimeout");
    Duration outerTimeout = invocationTimeout.isZero() ? Duration.ofHours(1) : invocationTimeout;
    if (!args.has("timeout_seconds")) {
      return outerTimeout;
    }
    Duration requestedTimeout =
        Duration.ofSeconds(optionalPositiveInt(args, "timeout_seconds", 1, MAX_TIMEOUT_SECONDS));
    return outerTimeout.compareTo(requestedTimeout) > 0 ? requestedTimeout : outerTimeout;
  }

  private ToolResult result(String callId, List<String> completeLines, int limit) throws Exception {
    boolean limited = completeLines.size() > limit;
    List<String> previewLines =
        new ArrayList<>(completeLines.subList(0, Math.min(limit, completeLines.size())));
    if (limited) {
      previewLines.add("");
      previewLines.add("[" + limit + " results limit reached. Refine the pattern or raise limit.]");
    }
    if (!limited) {
      return new ToolResult(
          callId,
          OutputLimiter.limit(
              String.join("\n", previewLines).getBytes(StandardCharsets.UTF_8),
              "text/plain",
              config),
          false,
          "{}",
          false);
    }
    String preview = String.join("\n", previewLines);
    byte[] previewBytes = preview.getBytes(StandardCharsets.UTF_8);
    if (OutputLimiter.exceeds(preview, previewBytes.length, config)) {
      preview =
          OutputLimiter.preview(preview, config)
              + "\n\n[Output truncated to the configured preview limits.]";
    }
    List<ToolContent> contents = new ArrayList<>();
    contents.add(new TextToolContent(preview));
    contents.add(
        new ResourceToolContent(
            config
                .resourceStore()
                .store(
                    String.join("\n", completeLines).getBytes(StandardCharsets.UTF_8),
                    "text/plain")));
    return new ToolResult(callId, contents, false, "{}", false);
  }
}
