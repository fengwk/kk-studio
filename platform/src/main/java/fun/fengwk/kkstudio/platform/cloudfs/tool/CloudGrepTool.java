package fun.fengwk.kkstudio.platform.cloudfs.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.re2j.Pattern;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.cloudfs.blob.BlobReadResult;
import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.ToolArtifactPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code cloud_grep} 工具实现。
 *
 * <p>在 Cloud File System 中检索文本内容（仅使用 RE2/J 或字面量搜索，严禁 java.util.regex，超限 early stop）。 遵循异步 Tool
 * SPI，在虚拟线程中执行并立即返回具备中断与取消能力的 handle。
 */
public final class CloudGrepTool implements Tool {

  public static final String NAME = "cloud_grep";
  public static final String VERSION = "1";
  public static final int DEFAULT_LIMIT = 100;
  public static final int MAX_LIMIT = 100_000;
  public static final int DEFAULT_TIMEOUT_SECONDS = 15;
  public static final int MAX_TIMEOUT_SECONDS = 3600;

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          CloudToolPrompts.prompt(NAME),
          NAME,
          CloudToolPrompts.schema(NAME),
          ToolSideEffect.READ_ONLY,
          Duration.ofHours(1));

  private final CloudFileSystemService fileSystemService;
  private final StorageBlobFileReader blobFileReader;

  public CloudGrepTool(
      CloudFileSystemService fileSystemService, StorageBlobFileReader blobFileReader) {
    this.fileSystemService = Objects.requireNonNull(fileSystemService, "fileSystemService");
    this.blobFileReader = blobFileReader;
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");

    Execution execution = new Execution(request, listener);
    execution.start();
    return execution;
  }

  private final class Execution implements ToolExecutionHandle, Runnable {

    private enum State {
      RUNNING,
      COMPLETED,
      FAILED,
      CANCELLED
    }

    private final ToolExecutionRequest request;
    private final ToolExecutionListener listener;
    private final Object lock = new Object();
    private State state = State.RUNNING;
    private volatile SearchControl control;
    private volatile Thread workerThread;

    private Execution(ToolExecutionRequest request, ToolExecutionListener listener) {
      this.request = request;
      this.listener = listener;
    }

    private void start() {
      synchronized (lock) {
        if (state != State.RUNNING) {
          return;
        }
      }
      try {
        Thread thread = Thread.ofVirtual().name("cloud-grep-call").unstarted(this);
        this.workerThread = thread;
        thread.start();
      } catch (Throwable t) {
        fail(t);
      }
    }

    @Override
    public void run() {
      try {
        synchronized (lock) {
          if (state != State.RUNNING) {
            return;
          }
        }

        JsonNode args = CloudToolArguments.parse(request.call().argumentsJson());
        String pattern = CloudToolArguments.requireString(args, "pattern");
        String rawPath = CloudToolArguments.requireString(args, "path");
        String include = CloudToolArguments.optionalString(args, "include", null);
        boolean ignoreCase = CloudToolArguments.optionalBoolean(args, "ignore_case", false);
        boolean literal = CloudToolArguments.optionalBoolean(args, "literal", false);
        boolean multiline = CloudToolArguments.optionalBoolean(args, "multiline", false);
        int limit =
            CloudToolArguments.optionalBoundedInt(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int timeoutSeconds =
            CloudToolArguments.optionalBoundedInt(
                args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);

        CloudPath path = CloudPath.of(rawPath);
        Pattern compiledPattern =
            literal ? null : CloudSearchSupport.compileRe2(pattern, ignoreCase, multiline);
        CloudGlobMatcher includeMatcher =
            include != null && !include.isBlank() ? CloudGlobMatcher.compile(include) : null;
        SearchControl searchControl =
            SearchControl.of(Duration.ofSeconds(timeoutSeconds), request.effectiveTimeout());
        this.control = searchControl;

        synchronized (lock) {
          if (state != State.RUNNING) {
            return;
          }
        }

        int targetCount = limit + 1;
        List<String> matchLines = new ArrayList<>();

        if (path.isArtifactPath()) {
          if (!ToolArtifactPath.isToolArtifactPath(path)
              || !ToolArtifactPath.EXTENSION_TXT.equals(ToolArtifactPath.parse(path).extension())) {
            throw new CloudPathForbiddenException(
                path,
                "cloud_grep on artifact paths is only permitted for exact canonical .txt files: "
                    + path);
          }
          grepSingleArtifact(
              path,
              pattern,
              compiledPattern,
              literal,
              ignoreCase,
              multiline,
              matchLines,
              targetCount,
              searchControl);
        } else {
          CloudNode targetNode = fileSystemService.getNode(path);
          if (targetNode.isText()) {
            grepSingleTextNode(
                path,
                pattern,
                compiledPattern,
                literal,
                ignoreCase,
                multiline,
                matchLines,
                targetCount,
                searchControl);
          } else if (targetNode.isDirectory()) {
            walkAndGrep(
                path,
                path,
                includeMatcher,
                pattern,
                compiledPattern,
                literal,
                ignoreCase,
                multiline,
                matchLines,
                targetCount,
                searchControl);
          } else {
            throw new IllegalArgumentException("Cannot grep non-text file in user tree: " + path);
          }
        }

        synchronized (lock) {
          if (state != State.RUNNING) {
            return;
          }
        }

        String outputText;
        if (matchLines.isEmpty()) {
          outputText = "No matches found";
        } else {
          boolean limitReached = matchLines.size() > limit;
          List<String> lines =
              new ArrayList<>(limitReached ? matchLines.subList(0, limit) : matchLines);
          if (limitReached) {
            lines.add("");
            lines.add("[" + limit + " results limit reached. Refine the pattern or raise limit.]");
          }
          outputText = String.join("\n", lines);
        }

        ToolResult result =
            new ToolResult(
                request.call().id(), List.of(new TextResultContent(outputText)), false, "{}");
        complete(ToolOutcome.withoutEffects(result));
      } catch (CloudFileSystemException | IllegalArgumentException e) {
        complete(ToolOutcome.withoutEffects(ToolResult.error(request.call().id(), e.getMessage())));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        String msg =
            e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : "Search operation timed out";
        complete(ToolOutcome.withoutEffects(ToolResult.error(request.call().id(), msg)));
      } catch (Throwable t) {
        fail(t);
      }
    }

    private void complete(ToolOutcome outcome) {
      synchronized (lock) {
        if (state == State.RUNNING) {
          state = State.COMPLETED;
          listener.onComplete(outcome);
        }
      }
    }

    private void fail(Throwable t) {
      synchronized (lock) {
        if (state == State.RUNNING) {
          state = State.FAILED;
          listener.onError(t);
        }
      }
    }

    @Override
    public void cancel() {
      SearchControl sc;
      Thread wt;
      synchronized (lock) {
        if (state != State.RUNNING) {
          return;
        }
        state = State.CANCELLED;
        sc = control;
        wt = workerThread;
      }
      if (sc != null) {
        sc.cancel();
      }
      if (wt != null) {
        wt.interrupt();
      }
    }

    @Override
    public boolean isCancelled() {
      synchronized (lock) {
        return state == State.CANCELLED;
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
      List<String> matchLines,
      int targetCount,
      SearchControl control)
      throws InterruptedException {
    CloudNode node = fileSystemService.getNode(path);
    if (!node.isBlob() || node.getBlobId() == null) {
      throw new IllegalStateException("Tool artifact is not a valid blob: " + path);
    }
    if (blobFileReader == null) {
      throw new IllegalStateException("Blob content reader is not available");
    }
    BlobReadResult readResult = blobFileReader.read(node.getBlobId());
    if (!(readResult instanceof BlobReadResult.Text textBlob)) {
      throw new IllegalArgumentException("Tool artifact is not valid UTF-8 text: " + path);
    }
    List<CloudSearchSupport.GrepMatch> fileMatches =
        searchSingleFile(
            textBlob.text(),
            pattern,
            compiledPattern,
            literal,
            ignoreCase,
            multiline,
            targetCount,
            control);
    for (CloudSearchSupport.GrepMatch m : fileMatches) {
      matchLines.add(path.value() + ":" + m.lineNumber() + ":" + m.excerpt());
    }
  }

  private void grepSingleTextNode(
      CloudPath path,
      String pattern,
      Pattern compiledPattern,
      boolean literal,
      boolean ignoreCase,
      boolean multiline,
      List<String> matchLines,
      int targetCount,
      SearchControl control)
      throws InterruptedException {
    CloudTextRevision rev = fileSystemService.readCurrentText(path);
    List<CloudSearchSupport.GrepMatch> fileMatches =
        searchSingleFile(
            rev.getContent(),
            pattern,
            compiledPattern,
            literal,
            ignoreCase,
            multiline,
            targetCount,
            control);
    for (CloudSearchSupport.GrepMatch m : fileMatches) {
      matchLines.add(path.value() + ":" + m.lineNumber() + ":" + m.excerpt());
    }
  }

  private void walkAndGrep(
      CloudPath searchRoot,
      CloudPath currentDir,
      CloudGlobMatcher includeMatcher,
      String pattern,
      Pattern compiledPattern,
      boolean literal,
      boolean ignoreCase,
      boolean multiline,
      List<String> matchLines,
      int targetCount,
      SearchControl control)
      throws InterruptedException {
    control.check();
    if (matchLines.size() >= targetCount) {
      return;
    }

    List<CloudNode> children = fileSystemService.listChildren(currentDir);
    for (CloudNode child : children) {
      control.check();
      if (matchLines.size() >= targetCount) {
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

      if (child.isText()) {
        if (includeMatcher == null || includeMatcher.matches(relativePath, child.getName())) {
          int remaining = targetCount - matchLines.size();
          if (remaining <= 0) {
            return;
          }
          CloudTextRevision rev = fileSystemService.readCurrentText(childPath);
          List<CloudSearchSupport.GrepMatch> fileMatches =
              searchSingleFile(
                  rev.getContent(),
                  pattern,
                  compiledPattern,
                  literal,
                  ignoreCase,
                  multiline,
                  remaining,
                  control);
          for (CloudSearchSupport.GrepMatch m : fileMatches) {
            matchLines.add(childPath.value() + ":" + m.lineNumber() + ":" + m.excerpt());
            if (matchLines.size() >= targetCount) {
              return;
            }
          }
        }
      } else if (child.isDirectory()) {
        walkAndGrep(
            searchRoot,
            childPath,
            includeMatcher,
            pattern,
            compiledPattern,
            literal,
            ignoreCase,
            multiline,
            matchLines,
            targetCount,
            control);
        if (matchLines.size() >= targetCount) {
          return;
        }
      }
    }
  }

  private List<CloudSearchSupport.GrepMatch> searchSingleFile(
      String content,
      String pattern,
      Pattern compiledPattern,
      boolean literal,
      boolean ignoreCase,
      boolean multiline,
      int maxMatches,
      SearchControl control)
      throws InterruptedException {
    if (literal) {
      return CloudSearchSupport.searchLiteral(
          content, pattern, ignoreCase, multiline, maxMatches, control);
    } else {
      return CloudSearchSupport.searchFile(
          content, compiledPattern, multiline, maxMatches, control);
    }
  }
}
