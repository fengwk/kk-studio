package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** 使用 RE2/J 正则搜索 environment 文件，并遵守分层 {@code .gitignore}。 */
public final class GrepCapability extends AbstractCodingCapability {

  private static final int MAX_DISPLAY_LINE_CHARS = 500;
  static final int DEFAULT_TIMEOUT_SECONDS = 15;
  static final int MAX_TIMEOUT_SECONDS = 3600;

  /** 只有多行模式需要整文件视图；单行模式流式扫描，因此不受此上界限制。 */
  private static final long MAX_MULTILINE_FILE_BYTES = 64 * 1024 * 1024L;

  public GrepCapability(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_GREP));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String sourcePattern = string(args, "pattern");
    Path workdir = EnvironmentPaths.workdir(string(args, "workdir"));
    Path path = EnvironmentPaths.existing(string(args, "path"), workdir);
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
    List<String> completeLines = new ArrayList<>();

    if (directFile) {
      if (!Files.isReadable(path)) {
        throw new IllegalArgumentException("path is not readable: " + displayPath(workdir, path));
      }
      if (!SearchFiles.isGitMetadata(path)) {
        searchInFile(
            workdir,
            path,
            path.getParent(),
            true,
            include,
            pattern,
            multiline,
            control,
            completeLines,
            limit);
      }
    } else {
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("path must be a regular file or directory");
      }
      SearchFiles.walk(
          workdir,
          path,
          control,
          file -> {
            control.check();
            searchInFile(
                workdir,
                file,
                path,
                false,
                include,
                pattern,
                multiline,
                control,
                completeLines,
                limit);
            return completeLines.size() <= limit;
          });
    }

    if (completeLines.isEmpty()) {
      return success(request.call().id(), "No matches found");
    }

    boolean limitReached = completeLines.size() > limit;
    int displayCount = Math.min(limit, completeLines.size());

    try (OutputSpool spool = new OutputSpool(config.textOutputStore(), request.call().id())) {
      for (int i = 0; i < displayCount; i++) {
        if (i > 0) {
          spool.write((int) '\n');
        }
        spool.write(completeLines.get(i).getBytes(StandardCharsets.UTF_8));
      }
      if (limitReached) {
        spool.write(
            ("\n\n[" + limit + " results limit reached. Refine the pattern or raise limit.]")
                .getBytes(StandardCharsets.UTF_8));
      }
      return spool.finish(false);
    }
  }

  private void searchInFile(
      Path workdir,
      Path file,
      Path includeRoot,
      boolean directFile,
      IncludePattern include,
      Pattern pattern,
      boolean multiline,
      SearchControl control,
      List<String> completeLines,
      int limit)
      throws Exception {
    if (includeRoot != null) {
      String includePath = SearchFiles.toPosix(includeRoot.relativize(file));
      if (!include.matches(includePath, file.getFileName().toString())) {
        return;
      }
    }
    String display = displayPath(workdir, file);
    if (multiline) {
      searchMultiline(file, display, directFile, pattern, control, completeLines, limit);
      return;
    }
    searchSingleLines(file, display, directFile, pattern, control, completeLines, limit);
  }

  /**
   * 单行模式流式扫描：内存占用与文件大小无关，因此 Daemon 自己或外部工具生成的超大文本（包括 {@code process.exec} 落盘的全文）都能被搜索，不存在 “文件超过 N
   * MiB 就拒绝”的限制。
   */
  private static void searchSingleLines(
      Path file,
      String display,
      boolean directFile,
      Pattern pattern,
      SearchControl control,
      List<String> completeLines,
      int limit)
      throws Exception {
    byte[] probe;
    TextStreams.Encoding encoding;
    try {
      probe = TextStreams.probe(file);
      encoding = TextStreams.detectEncoding(probe);
    } catch (IOException | SecurityException error) {
      if (directFile) {
        throw new IllegalArgumentException("path is not readable: " + display);
      }
      return;
    }
    if (encoding.looksBinary(probe)) {
      if (directFile) {
        throw new IllegalArgumentException("file appears to be binary: " + display);
      }
      return;
    }
    try {
      TextStreams.forEachLine(
          file,
          encoding,
          (lineNumber, line, truncated) -> {
            control.check();
            if (completeLines.size() > limit) {
              return false;
            }
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
              completeLines.add(
                  display
                      + ":"
                      + lineNumber
                      + ":"
                      + matchCenteredExcerpt(line, matcher.start(), matcher.end()));
            }
            return true;
          });
    } catch (IllegalArgumentException error) {
      // 流式严格解码发现非法 UTF-8：与整文件解码保持一致的“看似二进制文件”语义。
      if (directFile) {
        throw new IllegalArgumentException("file appears to be binary: " + display, error);
      }
    }
  }

  /** 多行模式仍需要整文件视图（跨行匹配），因此保留显式大小上界。 */
  private static void searchMultiline(
      Path file,
      String display,
      boolean directFile,
      Pattern pattern,
      SearchControl control,
      List<String> completeLines,
      int limit)
      throws Exception {
    long size;
    try {
      size = Files.size(file);
    } catch (IOException | SecurityException error) {
      if (directFile) {
        throw new IllegalArgumentException("path is not readable: " + display);
      }
      return;
    }
    if (size > MAX_MULTILINE_FILE_BYTES) {
      if (directFile) {
        throw new IllegalArgumentException(
            "multiline search requires loading the whole file; "
                + "file exceeds "
                + (MAX_MULTILINE_FILE_BYTES / (1024 * 1024))
                + " MiB maximum for multiline: "
                + display);
      }
      return;
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException | SecurityException error) {
      if (directFile) {
        throw new IllegalArgumentException("path is not readable: " + display);
      }
      return;
    }
    TextFileCodec.Decoded decoded;
    try {
      decoded = TextFileCodec.decode(bytes);
    } catch (IllegalArgumentException error) {
      if (directFile) {
        throw new IllegalArgumentException("file appears to be binary: " + display);
      }
      return;
    }

    TextLines text = TextLines.from(decoded.text());
    matchingMultiline(pattern, text, display, control, completeLines, limit);
  }

  private static void matchingMultiline(
      Pattern pattern,
      TextLines text,
      String displayPath,
      SearchControl control,
      List<String> completeLines,
      int limit)
      throws InterruptedException {
    if (text.lines().isEmpty()) {
      return;
    }
    Matcher matcher = pattern.matcher(text.text());
    boolean[] matched = new boolean[text.lines().size()];
    int[] matchStarts = new int[text.lines().size()];
    int[] matchEnds = new int[text.lines().size()];

    while (matcher.find()) {
      control.check();
      int start = lineIndex(text.lines(), matcher.start());
      int coveredEnd = matcher.end() > matcher.start() ? matcher.end() - 1 : matcher.start();
      int end = lineIndex(text.lines(), coveredEnd);
      for (int i = start; i <= end; i++) {
        if (!matched[i]) {
          matched[i] = true;
          matchStarts[i] = Math.max(0, matcher.start() - text.lines().get(i).startOffset());
          matchEnds[i] =
              Math.min(
                  text.lines().get(i).content().length(),
                  matcher.end() - text.lines().get(i).startOffset());
        }
      }
    }

    for (int i = 0; i < matched.length; i++) {
      control.check();
      if (matched[i]) {
        String content = text.lines().get(i).content();
        String excerpt = matchCenteredExcerpt(content, matchStarts[i], matchEnds[i]);
        completeLines.add(displayPath + ":" + (i + 1) + ":" + excerpt);
        if (completeLines.size() > limit) {
          break;
        }
      }
    }
  }

  static String matchCenteredExcerpt(String line, int matchStartChar, int matchEndChar) {
    int totalCp = line.codePointCount(0, line.length());
    if (totalCp <= MAX_DISPLAY_LINE_CHARS) {
      return line;
    }
    int matchStartCp = line.codePointCount(0, Math.min(line.length(), Math.max(0, matchStartChar)));
    int matchEndCp =
        line.codePointCount(0, Math.min(line.length(), Math.max(matchStartChar, matchEndChar)));
    int matchCenter = (matchStartCp + matchEndCp) / 2;
    int budget = 440;
    int startCp = Math.max(0, matchCenter - (budget / 2));
    int endCp = Math.min(totalCp, startCp + budget);
    if (endCp == totalCp) {
      startCp = Math.max(0, endCp - budget);
    }
    int startChar = line.offsetByCodePoints(0, startCp);
    int endChar = line.offsetByCodePoints(0, endCp);
    String excerpt = line.substring(startChar, endChar);
    StringBuilder sb = new StringBuilder();
    if (startCp > 0) {
      sb.append("... ");
    }
    sb.append(excerpt);
    if (endCp < totalCp) {
      sb.append(" ... (line truncated to 500 chars)");
    }
    return sb.toString();
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

  private static Pattern compilePattern(
      String source, boolean literal, boolean ignoreCase, boolean multiline) {
    int flags = 0;
    if (ignoreCase) {
      flags |= Pattern.CASE_INSENSITIVE;
    }
    if (multiline) {
      flags |= Pattern.MULTILINE;
    }
    try {
      return Pattern.compile(literal ? Pattern.quote(source) : source, flags);
    } catch (PatternSyntaxException error) {
      throw new IllegalArgumentException("Invalid regex: " + error.getMessage(), error);
    }
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
