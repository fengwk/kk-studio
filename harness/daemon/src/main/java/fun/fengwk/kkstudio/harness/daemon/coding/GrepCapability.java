package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.tool.ResourceToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 使用 Java NIO 与 regex 搜索 environment 文件，并遵守分层 {@code .gitignore}。 */
public final class GrepCapability extends AbstractCodingCapability {

  private static final int MAX_DISPLAY_LINE_CHARS = 500;
  static final int DEFAULT_TIMEOUT_SECONDS = 15;
  static final int MAX_TIMEOUT_SECONDS = 3600;

  public GrepCapability(CodingToolsConfig config, ExecutorService executor) {
    super(
        config, executor, EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_SEARCH));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String sourcePattern = string(args, "pattern");
    Path workdir = boundary.workdir(optionalString(args, "workdir"), request.workdir());
    Path path = boundary.existingWithoutSymlinks(string(args, "path"), workdir);
    int limit = optionalPositiveInt(args, "limit", 100, 100_000);
    Duration timeout =
        effectiveSearchTimeout(request.effectiveTimeout(), requestedTimeoutSeconds(args));
    SearchControl control = SearchControl.start(timeout, execution, "grep");
    boolean multiline = optionalBoolean(args, "multiline");
    control.check();
    Pattern pattern =
        compilePattern(
            sourcePattern,
            optionalBoolean(args, "literal"),
            optionalBoolean(args, "ignore_case"),
            multiline);
    IncludePattern include = IncludePattern.compile(optionalString(args, "include"));
    control.check();
    boolean directFile = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    List<Path> files = searchFiles(path, directFile, control);
    files =
        files.stream().sorted(Comparator.comparing(file -> displayPath(workdir, file))).toList();

    List<String> completeLines = new ArrayList<>();
    Path includeRoot = directFile ? path.getParent() : path;
    for (Path file : files) {
      control.check();
      String includePath = SearchFiles.toPosix(includeRoot.relativize(file));
      if (!include.matches(includePath, file.getFileName().toString())) {
        continue;
      }
      byte[] bytes;
      try {
        bytes = SearchFiles.readAllBytes(file, control);
      } catch (IOException | SecurityException error) {
        if (directFile) {
          throw new IllegalArgumentException("path is not readable: " + displayPath(workdir, file));
        }
        continue;
      }
      TextFileCodec.Decoded decoded;
      try {
        decoded = TextFileCodec.decode(bytes);
      } catch (IllegalArgumentException error) {
        if (!"file appears to be binary".equals(error.getMessage())) {
          throw error;
        }
        if (directFile) {
          throw new IllegalArgumentException(
              "file appears to be binary: " + displayPath(workdir, file));
        }
        continue;
      }
      TextLines text = TextLines.from(decoded.text());
      List<Integer> matchingLines =
          multiline
              ? matchingMultilineLines(pattern, text, control)
              : matchingSingleLines(pattern, text, control);
      String displayPath = displayPath(workdir, file);
      for (int lineNumber : matchingLines) {
        control.check();
        completeLines.add(
            displayPath + ":" + lineNumber + ":" + text.lines().get(lineNumber - 1).content());
      }
    }
    if (completeLines.isEmpty()) {
      return success(request.call().id(), "No matches found");
    }
    return result(request.call().id(), completeLines, limit);
  }

  static int requestedTimeoutSeconds(JsonNode args) {
    return optionalPositiveInt(
        args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
  }

  static Duration effectiveSearchTimeout(Duration invocationTimeout, int requestedTimeoutSeconds) {
    Objects.requireNonNull(invocationTimeout, "invocationTimeout");
    Duration requestedTimeout = Duration.ofSeconds(requestedTimeoutSeconds);
    return invocationTimeout.isZero() || invocationTimeout.compareTo(requestedTimeout) > 0
        ? requestedTimeout
        : invocationTimeout;
  }

  private List<Path> searchFiles(Path path, boolean directFile, SearchControl control)
      throws Exception {
    if (directFile) {
      if (!Files.isReadable(path)) {
        throw new IllegalArgumentException("path is not readable: " + path);
      }
      control.check();
      return SearchFiles.isGitMetadata(config.environmentRoot(), path) ? List.of() : List.of(path);
    }
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("path must be a regular file or directory");
    }
    return SearchFiles.collect(config.environmentRoot(), path, control);
  }

  private EnvironmentCapabilityResult result(String callId, List<String> completeLines, int limit)
      throws IOException {
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
    return new EnvironmentCapabilityResult(callId, contents, false, "{}");
  }

  private static Pattern compilePattern(
      String source, boolean literal, boolean ignoreCase, boolean multiline) {
    int flags = 0;
    if (ignoreCase) {
      flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    }
    if (multiline) {
      flags |= Pattern.MULTILINE;
    }
    try {
      return Pattern.compile(literal ? Pattern.quote(source) : source, flags);
    } catch (PatternSyntaxException error) {
      throw new IllegalArgumentException("Invalid regex: " + error.getDescription(), error);
    }
  }

  private static List<Integer> matchingSingleLines(
      Pattern pattern, TextLines text, SearchControl control) throws InterruptedException {
    List<Integer> matches = new ArrayList<>();
    for (int index = 0; index < text.lines().size(); index++) {
      control.check();
      if (pattern.matcher(text.lines().get(index).content()).find()) {
        matches.add(index + 1);
      }
    }
    return matches;
  }

  private static List<Integer> matchingMultilineLines(
      Pattern pattern, TextLines text, SearchControl control) throws InterruptedException {
    if (text.lines().isEmpty()) {
      return List.of();
    }
    boolean[] matched = new boolean[text.lines().size()];
    Matcher matcher = pattern.matcher(text.text());
    while (true) {
      control.check();
      if (!matcher.find()) {
        break;
      }
      int start = lineIndex(text.lines(), matcher.start());
      int coveredEnd = matcher.end() > matcher.start() ? matcher.end() - 1 : matcher.start();
      int end = lineIndex(text.lines(), coveredEnd);
      for (int index = start; index <= end; index++) {
        matched[index] = true;
      }
    }
    List<Integer> matches = new ArrayList<>();
    for (int index = 0; index < matched.length; index++) {
      if (matched[index]) {
        matches.add(index + 1);
      }
    }
    return matches;
  }

  private static int lineIndex(List<Line> lines, int offset) {
    int bounded = Math.max(0, offset);
    int low = 0;
    int high = lines.size() - 1;
    while (low < high) {
      int middle = (low + high + 1) >>> 1;
      if (lines.get(middle).startOffset() <= bounded) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return low;
  }

  private static String displayPath(Path workdir, Path file) {
    return SearchFiles.toPosix(workdir.relativize(file));
  }

  private static String truncateLine(String line) {
    if (line.codePointCount(0, line.length()) <= MAX_DISPLAY_LINE_CHARS) {
      return line;
    }
    int end = line.offsetByCodePoints(0, MAX_DISPLAY_LINE_CHARS);
    return line.substring(0, end) + "... (line truncated to 500 chars)";
  }

  private record IncludePattern(boolean pathPattern, GlobPattern pattern) {

    private static IncludePattern compile(String source) {
      return source == null
          ? new IncludePattern(false, null)
          : new IncludePattern(source.indexOf('/') >= 0, GlobPattern.compile(source));
    }

    private boolean matches(String path, String basename) {
      return pattern == null || pattern.matches(pathPattern ? path : basename);
    }
  }

  private record TextLines(String text, List<Line> lines) {

    private static TextLines from(String source) {
      String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
      if (normalized.isEmpty()) {
        return new TextLines(normalized, List.of());
      }
      String[] contents = normalized.split("\n", -1);
      int count = normalized.endsWith("\n") ? contents.length - 1 : contents.length;
      List<Line> lines = new ArrayList<>(count);
      int offset = 0;
      for (int index = 0; index < count; index++) {
        lines.add(new Line(offset, contents[index]));
        offset += contents[index].length() + 1;
      }
      return new TextLines(normalized, List.copyOf(lines));
    }
  }

  private record Line(int startOffset, String content) {}
}
