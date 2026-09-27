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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 使用 RE2/J 正则搜索 environment 文件，并遵守分层 {@code .gitignore}（见 {@link GitIgnoreRules}）。
 *
 * <p>匹配在完整内容上进行，展示缩略与匹配完整性分离：超长行只在“展示”时按字符预算居中截取，匹配本身仍然覆盖整行；多行模式需要整文件视图，因此保留显式字节上界。
 *
 * <p>超时、取消、资源上限与读取失败绝不报告为“无匹配”：{@code 无匹配} 只在确实完成了可搜索范围且没有任何命中时返回，其余情况要么显式失败，要么在结果里列出未能搜索的路径。
 *
 * <p>{@code workdir} 可选：省略时 {@code path} 必须是绝对路径，命中与错误信息按绝对路径展示，绝不推断 cwd、HOME
 * 或其它默认目录；提供时必须仍是现存可读的绝对目录，展示保持相对 workdir 的形态。
 *
 * <p>读取失败的粒度：探测阶段（前缀读取）失败记为“未搜索路径”；流式扫描中途的 I/O 失败直接让本次调用显式失败，不降级为“没有命中”。
 */
public final class GrepCapability extends AbstractCodingCapability {

  private static final int MAX_DISPLAY_LINE_CHARS = 500;

  /** 未搜索路径在结果摘要中最多列出的条目数。 */
  private static final int MAX_SKIP_DETAIL = 10;

  /** 多行模式需要整文件视图（跨行匹配），因此保留显式字节上界；超过即显式失败，绝不静默当作无匹配。 */
  private static final long MAX_MULTILINE_FILE_BYTES = 64 * 1024 * 1024L;

  public GrepCapability(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_GREP));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String sourcePattern = string(args, "pattern");
    String rawWorkdir = optionalString(args, "workdir");
    Path workdir = rawWorkdir == null ? null : EnvironmentPaths.workdir(rawWorkdir);
    Path path = EnvironmentPaths.existing(string(args, "path"), workdir);
    int limit = optionalPositiveInt(args, "limit", 100, 100_000);
    // 有效超时在 Platform 侧解析完成（definition 默认值或显式 timeout_seconds）；这里只消费它。
    Duration timeout = request.timeout();
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
    SearchResults results = new SearchResults(limit);

    if (directFile) {
      if (!Files.isReadable(path)) {
        throw new IllegalArgumentException("path is not readable: " + displayPath(workdir, path));
      }
      if (!SearchFiles.isGitMetadata(path)) {
        searchInFile(
            workdir, path, path.getParent(), true, include, pattern, multiline, control, results);
      }
    } else {
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("path must be a regular file or directory");
      }
      SearchFiles.Report report =
          SearchFiles.walk(
              path,
              control,
              file -> {
                control.check();
                searchInFile(
                    workdir, file, path, false, include, pattern, multiline, control, results);
                return !results.limitReached();
              });
      for (Path unreadable : report.unreadable()) {
        results.skip(displayPath(workdir, unreadable), "could not be read");
      }
    }
    return results.finish(request.call().id(), config, "No matches found");
  }

  private static void searchInFile(
      Path workdir,
      Path file,
      Path includeRoot,
      boolean directFile,
      IncludePattern include,
      Pattern pattern,
      boolean multiline,
      SearchControl control,
      SearchResults results)
      throws Exception {
    if (includeRoot != null) {
      String includePath = SearchFiles.toPosix(includeRoot.relativize(file));
      if (!include.matches(includePath, file.getFileName().toString())) {
        return;
      }
    }
    String display = displayPath(workdir, file);
    try {
      if (multiline) {
        searchMultiline(file, display, pattern, control, results);
      } else {
        searchSingleLines(file, display, pattern, control, results);
      }
    } catch (SearchFailureException error) {
      if (directFile) {
        throw error;
      }
      // 目录扫描中的二进制文件按既有语义静默跳过；其余不可完整搜索的原因必须显式上报。
      if (error.skipReason() != null) {
        results.skip(display, error.skipReason());
      }
    }
  }

  /**
   * 单行模式流式扫描：内存占用与文件大小无关，因此 Daemon 自己或外部工具生成的超大文本（包括 {@code process.exec} 落盘的全文）都能被搜索，不存在 “文件超过 N
   * MiB 就拒绝”的限制。
   *
   * <p>单行超过 {@link TextStreams#MAX_LINE_CHARS} 时不能只匹配前缀就宣称结果完整，因此升级为明确失败（目录扫描记为未搜索），而不是静默截断后返回无匹配。
   */
  private static void searchSingleLines(
      Path file, String display, Pattern pattern, SearchControl control, SearchResults results)
      throws Exception {
    byte[] probe;
    TextStreams.Encoding encoding;
    try {
      probe = TextStreams.probe(file);
      encoding = TextStreams.detectEncoding(probe);
    } catch (IOException | SecurityException error) {
      throw new SearchFailureException("path is not readable: " + display, "could not be read");
    }
    if (encoding.looksBinary(probe)) {
      throw new SearchFailureException("file appears to be binary: " + display, null);
    }
    try {
      TextStreams.forEachLine(
          file,
          encoding,
          (lineNumber, line, truncated) -> {
            control.check();
            if (results.limitReached()) {
              return false;
            }
            if (truncated) {
              throw new SearchFailureException(
                  "file has a line longer than "
                      + TextStreams.MAX_LINE_CHARS
                      + " characters and cannot be searched completely: "
                      + display,
                  "contains a line longer than " + TextStreams.MAX_LINE_CHARS + " characters");
            }
            Matcher matcher = pattern.matcher(control.deadlineChecked(line));
            if (matcher.find()) {
              if (!results.acceptMoreMatches()) {
                return false;
              }
              results.addLine(
                  display
                      + ":"
                      + lineNumber
                      + ":"
                      + matchCenteredExcerpt(line, matcher.start(), matcher.end()));
              results.endMatch();
            }
            return true;
          });
    } catch (SearchControl.CancelledException error) {
      throw error.interruption();
    } catch (TextStreams.DecodeException error) {
      // 流式严格解码发现非法 UTF-8：与整文件解码保持一致的“看似二进制文件”语义。
      throw new SearchFailureException("file appears to be binary: " + display, null, error);
    }
  }

  /** 多行模式仍需要整文件视图（跨行匹配），因此保留显式大小上界。 */
  private static void searchMultiline(
      Path file, String display, Pattern pattern, SearchControl control, SearchResults results)
      throws Exception {
    long size;
    try {
      size = Files.size(file);
    } catch (IOException | SecurityException error) {
      throw new SearchFailureException("path is not readable: " + display, "could not be read");
    }
    if (size > MAX_MULTILINE_FILE_BYTES) {
      throw new SearchFailureException(
          "multiline search requires loading the whole file; file exceeds "
              + (MAX_MULTILINE_FILE_BYTES / (1024 * 1024))
              + " MiB maximum for multiline: "
              + display,
          "exceeds " + (MAX_MULTILINE_FILE_BYTES / (1024 * 1024)) + " MiB multiline limit");
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(file);
    } catch (IOException | SecurityException error) {
      throw new SearchFailureException("path is not readable: " + display, "could not be read");
    }
    TextFileCodec.Decoded decoded;
    try {
      decoded = TextFileCodec.decode(bytes);
    } catch (IllegalArgumentException error) {
      throw new SearchFailureException("file appears to be binary: " + display, null, error);
    }
    matchingMultiline(pattern, TextLines.from(decoded.text()), display, control, results);
  }

  /**
   * 整文件跨行匹配：输入经 {@link SearchControl#deadlineChecked} 包装，使引擎在扫描字符时也能响应 deadline
   * 与取消。覆盖多行的一个命中按行顺序输出，已被前一个命中覆盖 的行只输出一次。
   */
  private static void matchingMultiline(
      Pattern pattern, TextLines text, String display, SearchControl control, SearchResults results)
      throws InterruptedException {
    if (text.lines().isEmpty()) {
      return;
    }
    Matcher matcher = pattern.matcher(control.deadlineChecked(text.text()));
    int emittedThroughLine = -1;
    try {
      while (matcher.find()) {
        control.check();
        if (!results.acceptMoreMatches()) {
          return;
        }
        int startLine = lineIndex(text.lines(), matcher.start());
        int coveredEnd = matcher.end() > matcher.start() ? matcher.end() - 1 : matcher.start();
        int endLine = lineIndex(text.lines(), coveredEnd);
        for (int index = Math.max(startLine, emittedThroughLine + 1); index <= endLine; index++) {
          Line line = text.lines().get(index);
          int matchStart = Math.max(0, matcher.start() - line.startOffset());
          int matchEnd = Math.min(line.content().length(), matcher.end() - line.startOffset());
          results.addLine(
              display
                  + ":"
                  + (index + 1)
                  + ":"
                  + matchCenteredExcerpt(line.content(), matchStart, matchEnd));
          emittedThroughLine = index;
        }
        results.endMatch();
      }
    } catch (SearchControl.CancelledException error) {
      throw error.interruption();
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
      throw new IllegalArgumentException(
          "Invalid regex: "
              + error.getMessage()
              + ". Use literal=true for exact text, or escape regex metacharacters.",
          error);
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

  /**
   * 命中收集与终态组装：区分“已完成范围内的命中”与“未能完整搜索的路径”。
   *
   * <p>{@code limit} 约束的是**命中数**（schema 语义）：单行模式下一个命中就是一行输出，多行模式下同一个命中可以覆盖多行，其覆盖行会整体输出。
   * 没有命中但同时存在未搜索路径时返回错误而不是 “No matches found”，避免把不完整的搜索伪装成确定的空结果。
   */
  private static final class SearchResults {

    private final int limit;
    private final List<String> lines = new ArrayList<>();
    private final Map<String, String> skipped = new LinkedHashMap<>();
    private int matches;
    private boolean moreMatches;

    private SearchResults(int limit) {
      this.limit = limit;
    }

    /** 是否已经确认存在超出 limit 的更多命中（结果不完整）。 */
    private boolean limitReached() {
      return moreMatches;
    }

    /** 还能接收一个命中时返回 true；否则记录“结果不完整”并返回 false。 */
    private boolean acceptMoreMatches() {
      if (matches < limit) {
        return true;
      }
      moreMatches = true;
      return false;
    }

    /** 追加该命中的一行输出；调用方必须在 {@link #acceptMoreMatches()} 为 true 时使用。 */
    private void addLine(String line) {
      lines.add(line);
    }

    /** 结束一个命中。 */
    private void endMatch() {
      matches++;
    }

    private void skip(String path, String reason) {
      skipped.putIfAbsent(path, reason);
    }

    private EnvironmentCapabilityResult finish(
        String callId, CodingToolsConfig config, String emptyMessage) throws IOException {
      if (lines.isEmpty()) {
        if (!skipped.isEmpty()) {
          return error(
              callId,
              "No matches found, but "
                  + skipped.size()
                  + " path(s) could not be searched: ["
                  + describeSkipped()
                  + "]");
        }
        return success(callId, emptyMessage);
      }
      try (OutputSpool spool = new OutputSpool(config.textOutputStore(), callId)) {
        for (int index = 0; index < lines.size(); index++) {
          if (index > 0) {
            spool.write((int) '\n');
          }
          spool.write(lines.get(index).getBytes(StandardCharsets.UTF_8));
        }
        if (moreMatches) {
          spool.write(
              ("\n\n[" + limit + " results limit reached. Refine the pattern or raise limit.]")
                  .getBytes(StandardCharsets.UTF_8));
        }
        if (!skipped.isEmpty()) {
          spool.write(
              ("\n\n["
                      + skipped.size()
                      + " path(s) could not be searched: "
                      + describeSkipped()
                      + "]")
                  .getBytes(StandardCharsets.UTF_8));
        }
        return spool.finish(false);
      }
    }

    private String describeSkipped() {
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

  /**
   * 单个目标文件无法被完整搜索：二进制、超出单行或多行资源上限、读取失败。
   *
   * <p>{@code skipReason} 为 {@code null} 表示目录扫描中应静默跳过（二进制）；非 {@code null} 表示必须作为“未搜索”显式上报。
   */
  private static final class SearchFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String skipReason;

    private SearchFailureException(String message, String skipReason) {
      super(message);
      this.skipReason = skipReason;
    }

    private SearchFailureException(String message, String skipReason, Throwable cause) {
      super(message, cause);
      this.skipReason = skipReason;
    }

    private String skipReason() {
      return skipReason;
    }
  }
}
