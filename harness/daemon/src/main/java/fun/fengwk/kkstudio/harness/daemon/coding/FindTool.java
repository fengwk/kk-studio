package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Finds gitignore-respecting workspace-relative paths through configurable fd or fdfind. */
public final class FindTool extends AbstractCodingTool {

  public FindTool(CodingToolsConfig config) {
    super(
        config,
        new ToolDescriptor(
            "find",
            "1",
            "Find files under a workspace path using fd while respecting .gitignore.",
            null,
            new ToolParamsSchema(
                "Find parameters",
                Map.of(
                    "pattern", new ToolStringSchema("Glob pattern"),
                    "path", new ToolStringSchema("Search directory"),
                    "workdir", new ToolStringSchema("Optional workspace-relative directory"),
                    "limit", new ToolIntegerSchema("Maximum results"),
                    "timeout_seconds", new ToolIntegerSchema("Search timeout in seconds")),
                Set.of("pattern", "path"),
                false),
            ToolExecutionMode.ENVIRONMENT,
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(15)));
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    Path path =
        boundary.existing(string(args, "path"), boundary.workdir(optionalString(args, "workdir")));
    if (!Files.isDirectory(path)) {
      throw new IllegalArgumentException("path must be a directory");
    }
    int limit = optionalPositiveInt(args, "limit", 1000, 100_000);
    int timeout = optionalPositiveInt(args, "timeout_seconds", 15, 3600);
    String pattern = string(args, "pattern");
    List<String> command = new ArrayList<>();
    command.add(config.fdExecutable());
    command.add("--glob");
    command.add("--color=never");
    command.add("--hidden");
    command.add("--no-require-git");
    command.add("--max-results");
    command.add(Integer.toString(limit + 1));
    if (pattern.contains("/") || pattern.contains(File.separator)) {
      command.add("--full-path");
    }
    command.add("--");
    command.add(pattern);
    command.add(".");
    Process process;
    try {
      process =
          new ProcessBuilder(command).directory(path.toFile()).redirectErrorStream(true).start();
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "fd executable is unavailable: " + config.fdExecutable(), error);
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Thread reader = new Thread(() -> copy(process.getInputStream(), bytes), "daemon-find-reader");
    reader.start();
    try {
      if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
        terminate(process);
        reader.join();
        throw new IllegalArgumentException("find timed out after " + timeout + " seconds");
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
    if (process.exitValue() != 0) {
      throw new IllegalArgumentException(output.isBlank() ? "fd failed" : output.strip());
    }
    if (output.isBlank()) {
      return success(request.call().id(), "No files found matching pattern");
    }
    List<String> lines = new ArrayList<>();
    for (String line : output.split("\\R")) {
      if (!line.isBlank()) {
        lines.add(line.replace('\\', '/').replaceFirst("^\\./", ""));
      }
    }
    boolean limited = lines.size() > limit;
    if (limited) {
      lines = new ArrayList<>(lines.subList(0, limit));
      lines.add("");
      lines.add("[" + limit + " results limit reached. Refine the pattern or raise limit.]");
    }
    return new ToolResult(
        request.call().id(),
        OutputLimiter.limit(
            String.join("\n", lines).getBytes(StandardCharsets.UTF_8), "text/plain", config),
        false,
        "{}",
        false);
  }

  private static void copy(InputStream input, ByteArrayOutputStream output) {
    try (input) {
      input.transferTo(output);
    } catch (IOException ignored) {
      // The parent process turns process failures and timeouts into structured tool errors.
    }
  }

  private static void terminate(Process process) {
    process.toHandle().descendants().forEach(child -> child.destroyForcibly());
    process.destroyForcibly();
  }
}
