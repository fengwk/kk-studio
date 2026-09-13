package fun.fengwk.kkstudio.platform.cloudfs.service.impl;

import com.google.re2j.Pattern;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.cloudfs.blob.BlobReadResult;
import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.ToolArtifactPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudQueryService;
import fun.fengwk.kkstudio.platform.cloudfs.tool.CloudGlobMatcher;
import fun.fengwk.kkstudio.platform.cloudfs.tool.CloudSearchSupport;
import fun.fengwk.kkstudio.platform.cloudfs.tool.SearchControl;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** {@link CloudQueryService} 核心实现。 */
@Service
public class CloudQueryServiceImpl implements CloudQueryService {

  private final CloudFileSystemService fileSystemService;
  private final StorageBlobFileReader blobFileReader;

  public CloudQueryServiceImpl(
      CloudFileSystemService fileSystemService, Optional<StorageBlobFileReader> blobFileReader) {
    this.fileSystemService = Objects.requireNonNull(fileSystemService, "fileSystemService");
    this.blobFileReader = Objects.requireNonNull(blobFileReader, "blobFileReader").orElse(null);
  }

  @Override
  public FindResult find(CloudPath rootPath, String pattern, int limit, Duration timeout) {
    try {
      return find(rootPath, pattern, limit, SearchControl.of(timeout));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Search operation timed out", e);
    }
  }

  @Override
  public FindResult find(CloudPath rootPath, String pattern, int limit, SearchControl control)
      throws InterruptedException {
    Objects.requireNonNull(rootPath, "rootPath");
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(control, "control");
    if (limit <= 0 || limit > 100_000) {
      throw new IllegalArgumentException("limit must be between 1 and 100,000: " + limit);
    }
    if (rootPath.isArtifactPath()) {
      throw new CloudPathForbiddenException(
          rootPath, "Enumeration of /.artifacts is forbidden: " + rootPath);
    }

    CloudNode rootNode = fileSystemService.getNode(rootPath);
    if (!rootNode.isDirectory()) {
      throw new IllegalArgumentException("path must be a directory: " + rootPath);
    }

    CloudGlobMatcher matcher = CloudGlobMatcher.compile(pattern);

    int targetCount = limit + 1;
    List<String> matches = new ArrayList<>();
    walkFind(rootPath, rootPath, matcher, matches, targetCount, control);

    boolean limited = matches.size() > limit;
    List<String> resultPaths = limited ? matches.subList(0, limit) : matches;
    return new FindResult(new ArrayList<>(resultPaths), limited);
  }

  @Override
  public GrepResult grep(
      CloudPath rootPath,
      String pattern,
      String include,
      boolean ignoreCase,
      boolean literal,
      boolean multiline,
      int limit,
      Duration timeout) {
    try {
      return grep(
          rootPath,
          pattern,
          include,
          ignoreCase,
          literal,
          multiline,
          limit,
          SearchControl.of(timeout));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Search operation timed out", e);
    }
  }

  @Override
  public GrepResult grep(
      CloudPath rootPath,
      String pattern,
      String include,
      boolean ignoreCase,
      boolean literal,
      boolean multiline,
      int limit,
      SearchControl control)
      throws InterruptedException {
    Objects.requireNonNull(rootPath, "rootPath");
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(control, "control");
    if (limit <= 0 || limit > 100_000) {
      throw new IllegalArgumentException("limit must be between 1 and 100,000: " + limit);
    }

    Pattern compiledPattern =
        literal ? null : CloudSearchSupport.compileRe2(pattern, ignoreCase, multiline);
    CloudGlobMatcher includeMatcher =
        include != null && !include.isBlank() ? CloudGlobMatcher.compile(include) : null;

    int targetCount = limit + 1;
    List<GrepMatch> matches = new ArrayList<>();

    if (rootPath.isArtifactPath()) {
      if (!ToolArtifactPath.isToolArtifactPath(rootPath)
          || !ToolArtifactPath.EXTENSION_TXT.equals(ToolArtifactPath.parse(rootPath).extension())) {
        throw new CloudPathForbiddenException(
            rootPath,
            "cloud_grep on artifact paths is only permitted for exact canonical .txt files: "
                + rootPath);
      }
      grepSingleArtifact(
          rootPath,
          pattern,
          compiledPattern,
          literal,
          ignoreCase,
          multiline,
          matches,
          targetCount,
          control);
    } else {
      CloudNode targetNode = fileSystemService.getNode(rootPath);
      if (targetNode.isText()) {
        grepSingleTextNode(
            rootPath,
            pattern,
            compiledPattern,
            literal,
            ignoreCase,
            multiline,
            matches,
            targetCount,
            control);
      } else if (targetNode.isDirectory()) {
        walkAndGrep(
            rootPath,
            rootPath,
            pattern,
            compiledPattern,
            includeMatcher,
            literal,
            ignoreCase,
            multiline,
            matches,
            targetCount,
            control);
      } else {
        throw new IllegalArgumentException("Cannot grep non-text file in user tree: " + rootPath);
      }
    }

    boolean limited = matches.size() > limit;
    List<GrepMatch> resultMatches = limited ? matches.subList(0, limit) : matches;
    return new GrepResult(new ArrayList<>(resultMatches), limited);
  }

  @Override
  public TextWindow windowText(
      String content, int offset, int limit, int inlineByteBudget, int maxLineCodePoints) {
    Objects.requireNonNull(content, "content");
    if (offset <= 0) {
      throw new IllegalArgumentException("offset must be positive: " + offset);
    }
    if (limit <= 0 || limit > 2000) {
      throw new IllegalArgumentException("limit must be between 1 and 2000: " + limit);
    }
    boolean endsWithNewline = content.endsWith("\n") || content.endsWith("\r");
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    String[] rawLines = normalized.split("\n", -1);
    List<String> rawList = new ArrayList<>(List.of(rawLines));
    if (endsWithNewline && !rawList.isEmpty()) {
      rawList.remove(rawList.size() - 1);
    }

    int total = rawList.size();
    if (offset > total) {
      return new TextWindow(offset, limit, total, null, endsWithNewline, false, List.of(), "");
    }

    int start = offset;
    int end = Math.min(total, start + limit - 1);
    List<TextLine> windowLines = new ArrayList<>();
    boolean hasTruncatedLine = false;
    int currentBytes = 0;
    int maxContentBytes = inlineByteBudget > 0 ? inlineByteBudget : 40 * 1024;
    int actualEnd = start - 1;

    for (int i = start; i <= end; i++) {
      String line = rawList.get(i - 1).replace("\r", "\\r").replace("\u0000", "\\0");
      int cpCount = line.codePointCount(0, line.length());
      boolean lineTruncated = false;
      if (maxLineCodePoints > 0 && cpCount > maxLineCodePoints) {
        hasTruncatedLine = true;
        lineTruncated = true;
        int endOffset = line.offsetByCodePoints(0, maxLineCodePoints);
        line =
            line.substring(0, endOffset)
                + "... (line truncated to "
                + maxLineCodePoints
                + " chars)";
      }
      byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
      if (i > start && currentBytes + lineBytes.length + 1 > maxContentBytes) {
        break;
      }
      windowLines.add(new TextLine(i, line, lineTruncated));
      currentBytes += lineBytes.length + 1;
      actualEnd = i;
      if (currentBytes >= maxContentBytes) {
        break;
      }
    }

    Integer nextOffset = (actualEnd < total) ? actualEnd + 1 : null;
    String joinedContent =
        windowLines.stream().map(TextLine::content).collect(Collectors.joining("\n"));
    if (endsWithNewline && actualEnd == total) {
      joinedContent = joinedContent + "\n";
    }
    return new TextWindow(
        start,
        limit,
        total,
        nextOffset,
        endsWithNewline,
        hasTruncatedLine,
        windowLines,
        joinedContent);
  }

  private void walkFind(
      CloudPath searchRoot,
      CloudPath currentDir,
      CloudGlobMatcher matcher,
      List<String> matches,
      int targetCount,
      SearchControl control)
      throws InterruptedException {
    control.check();
    if (matches.size() >= targetCount) {
      return;
    }

    List<CloudNode> children = fileSystemService.listChildren(currentDir);
    for (CloudNode child : children) {
      control.check();
      if (matches.size() >= targetCount) {
        return;
      }
      if (currentDir.isRoot() && ".artifacts".equals(child.getName())) {
        continue;
      }

      CloudPath childPath =
          currentDir.isRoot()
              ? CloudPath.of("/" + child.getName())
              : CloudPath.of(currentDir.value() + "/" + child.getName());

      String relativePath;
      if (searchRoot.isRoot()) {
        relativePath = childPath.value().substring(1);
      } else {
        relativePath = childPath.value().substring(searchRoot.value().length() + 1);
      }

      if (matcher.matches(relativePath, child.getName())) {
        String display = childPath.value() + (child.isDirectory() ? "/" : "");
        matches.add(display);
        if (matches.size() >= targetCount) {
          return;
        }
      }

      if (child.isDirectory()) {
        walkFind(searchRoot, childPath, matcher, matches, targetCount, control);
        if (matches.size() >= targetCount) {
          return;
        }
      }
    }
  }

  private void grepSingleArtifact(
      CloudPath path,
      String pattern,
      Pattern compiledPattern,
      boolean literal,
      boolean ignoreCase,
      boolean multiline,
      List<GrepMatch> matches,
      int targetCount,
      SearchControl control)
      throws InterruptedException {
    CloudNode node = fileSystemService.getNode(path);
    if (!node.isBlob() || node.getBlobId() == null) {
      throw new IllegalStateException("Tool artifact node is not a valid blob: " + path);
    }
    if (blobFileReader == null) {
      throw new IllegalStateException(
          "Blob content reader is not available in current environment");
    }
    BlobReadResult readResult = blobFileReader.read(node.getBlobId());
    if (readResult instanceof BlobReadResult.Text textBlob) {
      grepContent(
          path,
          textBlob.text(),
          pattern,
          compiledPattern,
          literal,
          ignoreCase,
          multiline,
          matches,
          targetCount,
          control);
      return;
    }
    throw new IllegalArgumentException("Tool artifact is not valid UTF-8 text: " + path);
  }

  private void grepSingleTextNode(
      CloudPath path,
      String pattern,
      Pattern compiledPattern,
      boolean literal,
      boolean ignoreCase,
      boolean multiline,
      List<GrepMatch> matches,
      int targetCount,
      SearchControl control)
      throws InterruptedException {
    CloudTextRevision current = fileSystemService.readCurrentText(path);
    grepContent(
        path,
        current.getContent(),
        pattern,
        compiledPattern,
        literal,
        ignoreCase,
        multiline,
        matches,
        targetCount,
        control);
  }

  private void walkAndGrep(
      CloudPath searchRoot,
      CloudPath currentDir,
      String pattern,
      Pattern compiledPattern,
      CloudGlobMatcher includeMatcher,
      boolean literal,
      boolean ignoreCase,
      boolean multiline,
      List<GrepMatch> matches,
      int targetCount,
      SearchControl control)
      throws InterruptedException {
    control.check();
    if (matches.size() >= targetCount) {
      return;
    }

    List<CloudNode> children = fileSystemService.listChildren(currentDir);
    for (CloudNode child : children) {
      control.check();
      if (matches.size() >= targetCount) {
        return;
      }
      if (currentDir.isRoot() && ".artifacts".equals(child.getName())) {
        continue;
      }

      CloudPath childPath =
          currentDir.isRoot()
              ? CloudPath.of("/" + child.getName())
              : CloudPath.of(currentDir.value() + "/" + child.getName());

      if (child.isDirectory()) {
        walkAndGrep(
            searchRoot,
            childPath,
            pattern,
            compiledPattern,
            includeMatcher,
            literal,
            ignoreCase,
            multiline,
            matches,
            targetCount,
            control);
        if (matches.size() >= targetCount) {
          return;
        }
      } else if (child.isText()) {
        String relativePath;
        if (searchRoot.isRoot()) {
          relativePath = childPath.value().substring(1);
        } else {
          relativePath = childPath.value().substring(searchRoot.value().length() + 1);
        }

        if (includeMatcher != null && !includeMatcher.matches(relativePath, child.getName())) {
          continue;
        }

        grepSingleTextNode(
            childPath,
            pattern,
            compiledPattern,
            literal,
            ignoreCase,
            multiline,
            matches,
            targetCount,
            control);
        if (matches.size() >= targetCount) {
          return;
        }
      }
    }
  }

  private void grepContent(
      CloudPath path,
      String text,
      String pattern,
      Pattern compiledPattern,
      boolean literal,
      boolean ignoreCase,
      boolean multiline,
      List<GrepMatch> matches,
      int targetCount,
      SearchControl control)
      throws InterruptedException {
    int remaining = targetCount - matches.size();
    if (remaining <= 0) {
      return;
    }

    List<CloudSearchSupport.GrepMatch> found;
    if (literal) {
      found =
          CloudSearchSupport.searchLiteral(
              text, pattern, ignoreCase, multiline, remaining, control);
    } else {
      found = CloudSearchSupport.searchFile(text, compiledPattern, multiline, remaining, control);
    }

    for (CloudSearchSupport.GrepMatch match : found) {
      matches.add(new GrepMatch(path, match.lineNumber(), match.excerpt()));
      if (matches.size() >= targetCount) {
        break;
      }
    }
  }
}
