package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolBooleanSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolIntegerSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Searches workspace files through configurable ripgrep while retaining its gitignore semantics.
 */
public final class GrepTool extends AbstractCodingTool {

  public GrepTool(CodingToolsConfig config) {
    super(
        config,
        new ToolDescriptor(
            "grep",
            "1",
            "Search text files under a workspace path using ripgrep.",
            null,
            new ToolParamsSchema(
                "Grep parameters",
                Map.of(
                    "pattern", new ToolStringSchema("Regular expression or literal"),
                    "path", new ToolStringSchema("Search file or directory"),
                    "workdir", new ToolStringSchema("Optional workspace-relative directory"),
                    "include", new ToolStringSchema("Optional glob"),
                    "ignore_case", new ToolBooleanSchema("Ignore case"),
                    "literal", new ToolBooleanSchema("Treat pattern literally"),
                    "multiline", new ToolBooleanSchema("Enable multiline pattern"),
                    "limit", new ToolIntegerSchema("Maximum reported matches"),
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
    String pattern = string(args, "pattern");
    Path path =
        boundary.existing(string(args, "path"), boundary.workdir(optionalString(args, "workdir")));
    int limit = optionalPositiveInt(args, "limit", 100, 100_000);
    int timeout = optionalPositiveInt(args, "timeout_seconds", 15, 3600);
    List<String> command = new ArrayList<>();
    command.add(config.rgExecutable());
    command.add("--line-number");
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
    command.add(path.toString());
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
    } catch (IOException error) {
      throw new IllegalArgumentException(
          "ripgrep executable is unavailable: " + config.rgExecutable(), error);
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Thread reader = new Thread(() -> copy(process.getInputStream(), bytes), "daemon-grep-reader");
    reader.start();
    try {
      if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
        terminate(process);
        reader.join();
        throw new IllegalArgumentException("grep timed out after " + timeout + " seconds");
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
    List<String> lines = output.isBlank() ? List.of() : List.of(output.split("\\R"));
    if (lines.isEmpty()) {
      return success(request.call().id(), "No matches found");
    }
    List<String> preview = new ArrayList<>(lines.subList(0, Math.min(limit, lines.size())));
    boolean limited = lines.size() > limit;
    if (limited) {
      preview.add("");
      preview.add("[" + limit + " results limit reached. Refine the pattern or raise limit.]");
    }
    List<ToolContent> contents;
    if (limited) {
      contents = new ArrayList<>();
      contents.add(new TextToolContent(String.join("\n", preview)));
      contents.add(
          new ArtifactToolContent(config.artifactSink().store(bytes.toByteArray(), "text/plain")));
    } else {
      contents =
          OutputLimiter.limit(
              String.join("\n", preview).getBytes(StandardCharsets.UTF_8), "text/plain", config);
    }
    return new ToolResult(request.call().id(), contents, false, "{}", false);
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
