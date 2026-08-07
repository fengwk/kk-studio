package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 通过可配置的 ripgrep 搜索 environment 文件，同时保留其 gitignore 语义。 */
public final class GrepTool extends AbstractCodingTool {

  private static final int MAX_DISPLAY_LINE_CHARS = 500;
  static final int DEFAULT_TIMEOUT_SECONDS = 15;
  static final int MAX_TIMEOUT_SECONDS = 3600;
  private static final Pattern LOCATION_PATTERN = Pattern.compile("^(.*?):(\\d+):(.*)$");

  public GrepTool(CodingToolsConfig config) {
    super(config, EnvironmentToolCatalog.require("grep"));
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String pattern = string(args, "pattern");
    Path workdir = boundary.workdir(optionalString(args, "workdir"));
    Path path = boundary.existing(string(args, "path"), workdir);
    int limit = optionalPositiveInt(args, "limit", 100, 100_000);
    Duration timeout =
        effectiveProcessTimeout(request.effectiveTimeout(), requestedTimeoutSeconds(args));
    List<String> command = new ArrayList<>();
    command.add(config.rgExecutable());
    command.add("--line-number");
    command.add("--with-filename");
    command.add("--color=never");
    command.add("--hidden");
    command.add("--no-messages");
    if (optionalBoolean(args, "ignore_case")) {
      command.add("--ignore-case");
    }
    if (optionalBoolean(args, "literal")) {
      command.add("--fixed-strings");
    }
    if (optionalBoolean(args, "multiline")) {
      command.add("--multiline");
    }
    String include = optionalString(args, "include");
    if (include != null) {
      command.add("--glob");
      command.add(include);
    }
    command.add("--");
    command.add(pattern);
    Path relativePath = workdir.relativize(path);
    command.add(relativePath.toString().isEmpty() ? "." : relativePath.toString());
    Process process;
    try {
      process =
          new ProcessBuilder(command).directory(workdir.toFile()).redirectErrorStream(true).start();
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "ripgrep executable is unavailable: " + config.rgExecutable(), error);
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Thread reader = new Thread(() -> copy(process.getInputStream(), bytes), "daemon-grep-reader");
    reader.setDaemon(true);
    reader.start();
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        terminate(process);
        reader.join();
        throw new IllegalArgumentException(
            "grep timed out after " + timeout.toMillis() + " milliseconds");
      }
      reader.join();
    } catch (InterruptedException error) {
      terminate(process);
      reader.join();
      throw error;
    }
    if (execution.isCancelled()) {
      throw new InterruptedException();
    }
    String output = bytes.toString(StandardCharsets.UTF_8);
    if (process.exitValue() > 1) {
      throw new IllegalArgumentException(output.isBlank() ? "ripgrep failed" : output.strip());
    }
    if (output.isBlank()) {
      return success(request.call().id(), "No matches found");
    }

    List<String> completeLines =
        List.of(output.split("\\R")).stream().map(line -> normalizeLine(line, workdir)).toList();
    boolean resultLimited = completeLines.size() > limit;
    List<String> previewLines =
        new ArrayList<>(completeLines.subList(0, Math.min(limit, completeLines.size())));
    boolean lineLimited = false;
    for (int index = 0; index < previewLines.size(); index++) {
      String line = previewLines.get(index);
      String bounded = truncateLine(line);
      lineLimited |= !bounded.equals(line);
      previewLines.set(index, bounded);
    }
    if (lineLimited) {
      previewLines.add("");
      previewLines.add("[Some matching lines were truncated to 500 characters.]");
    }
    if (resultLimited) {
      previewLines.add("");
      previewLines.add("[" + limit + " results limit reached. Refine the pattern or raise limit.]");
    }

    String preview = String.join("\n", previewLines);
    byte[] previewBytes = preview.getBytes(StandardCharsets.UTF_8);
    boolean outputLimited = OutputLimiter.exceeds(preview, previewBytes.length, config);
    if (outputLimited) {
      preview =
          OutputLimiter.preview(preview, config)
              + "\n\n[Output truncated to the configured preview limits.]";
    }
    byte[] completeBytes = String.join("\n", completeLines).getBytes(StandardCharsets.UTF_8);
    boolean truncated = lineLimited || resultLimited || outputLimited;
    List<ToolContent> contents = new ArrayList<>();
    contents.add(new TextToolContent(preview));
    if (truncated) {
      contents.add(
          new ResourceToolContent(config.resourceStore().store(completeBytes, "text/plain")));
    }
    return new ToolResult(request.call().id(), contents, false, "{}", false);
  }

  static int requestedTimeoutSeconds(JsonNode args) {
    return optionalPositiveInt(
        args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
  }

  static Duration effectiveProcessTimeout(Duration invocationTimeout, int requestedTimeoutSeconds) {
    Objects.requireNonNull(invocationTimeout, "invocationTimeout");
    Duration requestedTimeout = Duration.ofSeconds(requestedTimeoutSeconds);
    return invocationTimeout.isZero() || invocationTimeout.compareTo(requestedTimeout) > 0
        ? requestedTimeout
        : invocationTimeout;
  }

  private String normalizeLine(String line, Path workdir) {
    Matcher matcher = LOCATION_PATTERN.matcher(line);
    if (!matcher.matches()) {
      return line;
    }
    String displayPath = matcher.group(1);
    try {
      Path resultPath = Path.of(displayPath);
      if (resultPath.isAbsolute()) {
        Path normalized = resultPath.normalize();
        if (normalized.startsWith(config.environmentRoot())) {
          displayPath = workdir.relativize(normalized).toString();
        }
      }
    } catch (RuntimeException ignored) {
      // 当路径无法在当前平台表示时，保留子进程输出。
    }
    return displayPath.replace('\\', '/') + ":" + matcher.group(2) + ":" + matcher.group(3);
  }

  private static String truncateLine(String line) {
    if (line.codePointCount(0, line.length()) <= MAX_DISPLAY_LINE_CHARS) {
      return line;
    }
    int end = line.offsetByCodePoints(0, MAX_DISPLAY_LINE_CHARS);
    return line.substring(0, end) + "... (line truncated to 500 chars)";
  }

  private static void copy(InputStream input, ByteArrayOutputStream output) {
    try (input) {
      input.transferTo(output);
    } catch (IOException ignored) {
      // 父进程会把进程失败与超时转换为结构化的 tool errors。
    }
  }

  private static void terminate(Process process) {
    process.toHandle().descendants().forEach(child -> child.destroyForcibly());
    process.destroyForcibly();
  }
}
