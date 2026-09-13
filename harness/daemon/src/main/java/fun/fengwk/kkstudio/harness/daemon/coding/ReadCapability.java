package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/** 读取有界文本窗口或确定性的目录清单。 */
public final class ReadCapability extends AbstractCodingCapability {

  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 2000;
  private static final long MAX_FILE_BYTES = 64 * 1024 * 1024L;
  private static final int MAX_RESPONSE_BYTES = 48 * 1024;

  public ReadCapability(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String rawPath = string(args, "path");
    Path workdir = EnvironmentPaths.workdir(string(args, "workdir"));
    Path path = EnvironmentPaths.existing(rawPath, workdir);
    String displayPath = EnvironmentPaths.displayPath(path, workdir, rawPath);

    if (Files.isDirectory(path)) {
      List<String> names;
      try (var entries = Files.list(path)) {
        names =
            entries
                .map(ReadCapability::directoryEntryName)
                .sorted(Comparator.naturalOrder())
                .toList();
      }
      int offset = optionalPositiveInt(args, "offset", 1, Integer.MAX_VALUE);
      int limit = optionalPositiveInt(args, "limit", DEFAULT_LIMIT, MAX_LIMIT);
      int totalEntries = names.size();
      int start = Math.min(offset, totalEntries + 1);
      int end = Math.min(totalEntries, start + limit - 1);

      List<String> output = new ArrayList<>();
      output.add("path: " + displayPath);
      output.add("kind: directory");
      output.add("");

      int currentBytes = 0;
      for (String headerLine : output) {
        currentBytes += headerLine.getBytes(StandardCharsets.UTF_8).length + 1;
      }

      int actualEnd = start - 1;
      if (start <= totalEntries) {
        for (int i = start; i <= end; i++) {
          String entry = names.get(i - 1);
          int entryBytes = entry.getBytes(StandardCharsets.UTF_8).length + 1;
          if (currentBytes + entryBytes + 150 > MAX_RESPONSE_BYTES) {
            break;
          }
          output.add(entry);
          currentBytes += entryBytes;
          actualEnd = i;
        }
      }

      if (offset > totalEntries) {
        output.add("");
        output.add("[Showing 0 entries of " + totalEntries + ".]");
      } else if (actualEnd < totalEntries) {
        output.add("");
        output.add(
            "[Showing entries "
                + start
                + "-"
                + actualEnd
                + " of "
                + totalEntries
                + ". Re-run read with offset="
                + (actualEnd + 1)
                + " to continue.]");
      }

      return EnvironmentCapabilityResult.text(request.call().id(), String.join("\n", output));
    }

    long fileSize = Files.size(path);
    if (fileSize > MAX_FILE_BYTES) {
      throw new IllegalArgumentException(
          "file exceeds 64 MiB maximum read limit: " + fileSize + " bytes");
    }
    byte[] bytes = Files.readAllBytes(path);

    String imageMime = detectImageMediaType(bytes);
    if (imageMime != null) {
      DaemonResourceRef stored = config.resourceStore().store(bytes, imageMime);
      ResourceRef ref =
          new ResourceRef(
              stored.uri(), stored.mediaType(), stored.name(), stored.size(), stored.sha256());
      return new EnvironmentCapabilityResult(
          request.call().id(), List.of(new ResourceResultContent(ref)), false, "{}");
    }

    TextFileCodec.Decoded decoded = TextFileCodec.decode(bytes);
    int offset = optionalPositiveInt(args, "offset", 1, Integer.MAX_VALUE);
    int limit = optionalPositiveInt(args, "limit", DEFAULT_LIMIT, MAX_LIMIT);
    String original = decoded.text();
    boolean endsWithNewline = original.endsWith("\n") || original.endsWith("\r");
    String normalized = original.replace("\r\n", "\n").replace('\r', '\n');
    List<String> lines =
        original.isEmpty() ? List.of() : new ArrayList<>(List.of(normalized.split("\n", -1)));
    if (endsWithNewline && !lines.isEmpty()) {
      lines.remove(lines.size() - 1);
    }
    int totalLines = lines.size();
    int start = Math.min(offset, totalLines + 1);
    int end = Math.min(totalLines, start + limit - 1);

    String lang = detectLanguage(path);
    String lspStatus =
        config.lspBridgeCommand() != null
            ? ("supported" + (lang != null ? " (" + lang + ")" : ""))
            : "unsupported";

    List<String> output = new ArrayList<>();
    output.add("path: " + displayPath);
    output.add("ends_with_newline: " + (endsWithNewline ? "yes" : "no"));
    output.add("lsp: " + lspStatus);
    output.add("");

    int currentBytes = 0;
    for (String headerLine : output) {
      currentBytes += headerLine.getBytes(StandardCharsets.UTF_8).length + 1;
    }

    int width = Math.max(1, Integer.toString(Math.max(1, totalLines)).length());
    boolean hasLineTruncation = false;
    int actualEnd = start - 1;

    if (start <= totalLines) {
      for (int index = start; index <= end; index++) {
        String line = lines.get(index - 1).replace("\r", "\\r").replace("\u0000", "\\0");
        int cpCount = line.codePointCount(0, line.length());
        if (cpCount > 2000) {
          int cut = line.offsetByCodePoints(0, 2000);
          line = line.substring(0, cut) + "... (line truncated to 2000 chars)";
          hasLineTruncation = true;
        }
        String formatted = String.format("%" + width + "d|%s", index, line);
        int lineBytes = formatted.getBytes(StandardCharsets.UTF_8).length + 1;
        if (currentBytes + lineBytes + 200 > MAX_RESPONSE_BYTES) {
          break;
        }
        output.add(formatted);
        currentBytes += lineBytes;
        actualEnd = index;
      }
    }

    if (offset > totalLines) {
      output.add("");
      output.add("[Showing 0 lines of " + totalLines + ".]");
    } else if (actualEnd < totalLines) {
      output.add("");
      output.add(
          "[Showing lines "
              + start
              + "-"
              + actualEnd
              + " of "
              + totalLines
              + ". Re-run read with offset="
              + (actualEnd + 1)
              + " to continue.]");
    }
    if (hasLineTruncation) {
      output.add("Note: one or more lines were truncated to 2000 characters.");
    }

    return EnvironmentCapabilityResult.text(request.call().id(), String.join("\n", output));
  }

  static String detectImageMediaType(byte[] bytes) {
    if (bytes == null || bytes.length < 3) {
      return null;
    }
    if (bytes.length >= 8
        && (bytes[0] & 0xFF) == 0x89
        && bytes[1] == 'P'
        && bytes[2] == 'N'
        && bytes[3] == 'G'
        && bytes[4] == 0x0D
        && bytes[5] == 0x0A
        && bytes[6] == 0x1A
        && bytes[7] == 0x0A) {
      return "image/png";
    }
    if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (bytes.length >= 6
        && bytes[0] == 'G'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == '8'
        && (bytes[4] == '7' || bytes[4] == '9')
        && bytes[5] == 'a') {
      return "image/gif";
    }
    if (bytes.length >= 12
        && bytes[0] == 'R'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == 'F'
        && bytes[8] == 'W'
        && bytes[9] == 'E'
        && bytes[10] == 'B'
        && bytes[11] == 'P') {
      return "image/webp";
    }
    return null;
  }

  static String detectLanguage(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return null;
    }
    String name = fileName.toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".java")) {
      return "java";
    }
    if (name.endsWith(".ts")) {
      return "typescript";
    }
    if (name.endsWith(".js")) {
      return "javascript";
    }
    if (name.endsWith(".py")) {
      return "python";
    }
    if (name.endsWith(".go")) {
      return "go";
    }
    if (name.endsWith(".rs")) {
      return "rust";
    }
    if (name.endsWith(".c") || name.endsWith(".h")) {
      return "c";
    }
    if (name.endsWith(".cpp") || name.endsWith(".hpp") || name.endsWith(".cc")) {
      return "cpp";
    }
    return null;
  }

  private static String directoryEntryName(Path entry) {
    Path fileName = entry.getFileName();
    String name = fileName == null ? entry.toString() : fileName.toString();
    return name + (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ? "/" : "");
  }
}
