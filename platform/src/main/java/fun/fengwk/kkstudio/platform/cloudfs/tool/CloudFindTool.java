package fun.fengwk.kkstudio.platform.cloudfs.tool;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code cloud_find} 工具实现。
 *
 * <p>在 Cloud File System 中按 glob 模式查找文件和目录（不读取内容，自动跳过 {@code /.artifacts}，超限 early stop）。 遵循异步 Tool
 * SPI，在虚拟线程中执行并立即返回具备中断与取消能力的 handle。
 */
public final class CloudFindTool implements Tool {

  public static final String NAME = "cloud_find";
  public static final String VERSION = "1";
  public static final int DEFAULT_LIMIT = 200;
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

  public CloudFindTool(CloudFileSystemService fileSystemService) {
    this.fileSystemService = Objects.requireNonNull(fileSystemService, "fileSystemService");
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
        Thread thread = Thread.ofVirtual().name("cloud-find-call").unstarted(this);
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
        int limit =
            CloudToolArguments.optionalBoundedInt(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int timeoutSeconds =
            CloudToolArguments.optionalBoundedInt(
                args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, 1, MAX_TIMEOUT_SECONDS);

        CloudPath rootPath = CloudPath.of(rawPath);
        if (rootPath.isArtifactPath()) {
          throw new CloudPathForbiddenException(
              rootPath, "Enumeration of /.artifacts is forbidden: " + rootPath);
        }

        CloudNode rootNode = fileSystemService.getNode(rootPath);
        if (!rootNode.isDirectory()) {
          throw new IllegalArgumentException("path must be a directory: " + rootPath);
        }

        CloudGlobMatcher matcher = CloudGlobMatcher.compile(pattern);
        SearchControl searchControl =
            SearchControl.of(Duration.ofSeconds(timeoutSeconds), request.effectiveTimeout());
        this.control = searchControl;

        synchronized (lock) {
          if (state != State.RUNNING) {
            return;
          }
        }

        int targetCount = limit + 1;
        List<String> matches = new ArrayList<>();
        walk(rootPath, rootPath, matcher, matches, targetCount, searchControl);

        synchronized (lock) {
          if (state != State.RUNNING) {
            return;
          }
        }

        boolean limitReached = matches.size() > limit;
        List<String> outputLines =
            new ArrayList<>(limitReached ? matches.subList(0, limit) : matches);
        if (limitReached) {
          outputLines.add("");
          outputLines.add(
              "[" + limit + " results limit reached. Refine the pattern or raise limit.]");
        }

        String outputText = String.join("\n", outputLines);
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

  private void walk(
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

      // 计算相对搜索根的路径
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
        walk(searchRoot, childPath, matcher, matches, targetCount, control);
        if (matches.size() >= targetCount) {
          return;
        }
      }
    }
  }
}
