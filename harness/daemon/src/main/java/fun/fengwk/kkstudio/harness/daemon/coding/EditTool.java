package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在保留文件表示形式的前提下执行确定性的精确文本替换。 */
public final class EditTool extends AbstractCodingTool {

  public EditTool(CodingToolsConfig config) {
    super(config, EnvironmentToolCatalog.require("edit"));
  }

  @Override
  ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String rawPath = string(args, "path");
    String oldText = string(args, "old_string");
    String newText = string(args, "new_string");
    if (oldText.equals(newText)) {
      throw new IllegalArgumentException("old_string and new_string must differ");
    }
    if (oldText.isEmpty()) {
      throw new IllegalArgumentException("old_string must not be empty");
    }
    Path path =
        boundary.existing(
            rawPath, boundary.workdir(optionalString(args, "workdir"), request.workdir()));
    if (Files.isDirectory(path)) {
      throw new IllegalArgumentException("path must be a file: " + rawPath);
    }
    ReentrantLock lock = FileMutations.lock(path);
    try {
      TextFileCodec.Decoded decoded = TextFileCodec.decode(Files.readAllBytes(path));
      String source = decoded.text();
      String oldValue = normalizeNewlines(oldText, source);
      String newValue = normalizeNewlines(newText, source);
      int matches = count(source, oldValue);
      if (matches == 0) {
        throw new IllegalArgumentException("old_string was not found in file");
      }
      boolean replaceAll = optionalBoolean(args, "replace_all");
      if (!replaceAll && matches != 1) {
        throw new IllegalArgumentException(
            "Found " + matches + " exact matches; use replace_all to change every match");
      }
      String result =
          replaceAll
              ? source.replace(oldValue, newValue)
              : source.replaceFirst(Pattern.quote(oldValue), Matcher.quoteReplacement(newValue));
      if (execution.isCancelled()) {
        throw new InterruptedException();
      }
      Files.write(path, TextFileCodec.encode(result, decoded.charset(), decoded.bomLength()));
      int replacements = replaceAll ? matches : 1;
      return success(
          request.call().id(),
          "Edited "
              + rawPath
              + " successfully.\nReplacements: "
              + replacements
              + "\n\ndiff:\n-"
              + summary(oldText)
              + "\n+"
              + summary(newText));
    } finally {
      lock.unlock();
    }
  }

  private static String normalizeNewlines(String value, String source) {
    return source.contains("\r\n") ? value.replace("\r\n", "\n").replace("\n", "\r\n") : value;
  }

  private static int count(String source, String value) {
    int count = 0;
    int position = 0;
    while ((position = source.indexOf(value, position)) >= 0) {
      count++;
      position += value.length();
    }
    return count;
  }

  private static String summary(String value) {
    return value.length() <= 1000 ? value : value.substring(0, 1000) + "... (diff text truncated)";
  }
}
