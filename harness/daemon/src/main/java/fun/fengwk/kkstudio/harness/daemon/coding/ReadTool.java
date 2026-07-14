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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads bounded text windows or deterministic directory listings. */
public final class ReadTool extends AbstractCodingTool {

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 2000;

  public ReadTool(CodingToolsConfig config) {
    super(
        config,
        new ToolDescriptor(
            "read",
            "1",
            "Read a file or directory inside the environment workspace.",
            null,
            new ToolParamsSchema(
                "Read parameters",
                Map.of(
                    "path", new ToolStringSchema("File or directory path"),
                    "workdir", new ToolStringSchema("Optional workspace-relative directory"),
                    "offset", new ToolIntegerSchema("One-based line offset"),
                    "limit", new ToolIntegerSchema("Maximum number of lines")),
                Set.of("path"),
                false),
            ToolExecutionMode.ENVIRONMENT,
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1)));
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String rawPath = string(args, "path");
    Path path = boundary.existing(rawPath, boundary.workdir(optionalString(args, "workdir")));
    if (Files.isDirectory(path)) {
      List<String> names;
      try (var entries = Files.list(path)) {
        names =
            entries
                .map(
                    entry ->
                        entry.getFileName().toString()
                            + (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ? "/" : ""))
                .sorted(Comparator.naturalOrder())
                .toList();
      }
      return new ToolResult(
          request.call().id(),
          OutputLimiter.limit(
              String.join("\n", prepend("path: " + rawPath, "kind: directory", "", names))
                  .getBytes(StandardCharsets.UTF_8),
              "text/plain",
              config),
          false,
          "{}",
          false);
    }
    byte[] bytes = Files.readAllBytes(path);
    TextFileCodec.Decoded decoded;
    try {
      decoded = TextFileCodec.decode(bytes);
    } catch (IllegalArgumentException error) {
      if ("file appears to be binary".equals(error.getMessage())) {
        return new ToolResult(
            request.call().id(),
            OutputLimiter.limit(bytes, "application/octet-stream", config),
            false,
            "{}",
            false);
      }
      throw error;
    }
    int offset = optionalPositiveInt(args, "offset", 1, Integer.MAX_VALUE);
    int limit = optionalPositiveInt(args, "limit", DEFAULT_LIMIT, MAX_LIMIT);
    String original = decoded.text();
    boolean endsWithNewline = original.endsWith("\n") || original.endsWith("\r");
    String normalized = original.replace("\r\n", "\n").replace('\r', '\n');
    List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
    if (endsWithNewline && !lines.isEmpty()) {
      lines.remove(lines.size() - 1);
    }
    int start = Math.min(offset, lines.size() + 1);
    int end = Math.min(lines.size(), start + limit - 1);
    int width = Math.max(1, Integer.toString(Math.max(1, lines.size())).length());
    List<String> output = new ArrayList<>();
    output.add("path: " + rawPath);
    output.add("ends_with_newline: " + (endsWithNewline ? "yes" : "no"));
    output.add("lsp: unsupported");
    output.add("");
    for (int index = start; index <= end; index++) {
      String line = lines.get(index - 1).replace("\r", "\\r").replace("\u0000", "\\0");
      if (line.length() > 2000) {
        line = line.substring(0, 2000) + "... (line truncated to 2000 chars)";
      }
      output.add(String.format("%" + width + "d|%s", index, line));
    }
    if (end < lines.size()) {
      output.add("");
      output.add(
          "[Showing lines "
              + start
              + "-"
              + end
              + " of "
              + lines.size()
              + ". Re-run read with offset="
              + (end + 1)
              + " to continue.]");
    }
    return new ToolResult(
        request.call().id(),
        OutputLimiter.limit(
            String.join("\n", output).getBytes(StandardCharsets.UTF_8), "text/plain", config),
        false,
        "{}",
        false);
  }

  private static List<String> prepend(
      String first, String second, String third, List<String> remaining) {
    List<String> result = new ArrayList<>(List.of(first, second, third));
    result.addAll(remaining);
    return result;
  }
}
