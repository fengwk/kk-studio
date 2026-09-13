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
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudQueryService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code cloud_grep} 工具实现。
 *
 * <p>在 Cloud File System 中检索文本内容（仅使用 RE2/J 或字面量搜索，严禁 java.util.regex，超限 early stop）。遵循异步 Tool
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

  private final CloudQueryService queryService;

  public CloudGrepTool(CloudQueryService queryService) {
    this.queryService = Objects.requireNonNull(queryService, "queryService");
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
        SearchControl searchControl =
            SearchControl.of(Duration.ofSeconds(timeoutSeconds), request.effectiveTimeout());
        this.control = searchControl;

        synchronized (lock) {
          if (state != State.RUNNING) {
            return;
          }
        }

        CloudQueryService.GrepResult grepResult =
            queryService.grep(
                path, pattern, include, ignoreCase, literal, multiline, limit, searchControl);

        synchronized (lock) {
          if (state != State.RUNNING) {
            return;
          }
        }

        String outputText;
        if (grepResult.matches().isEmpty()) {
          outputText = "No matches found";
        } else {
          List<String> lines = new ArrayList<>();
          for (CloudQueryService.GrepMatch m : grepResult.matches()) {
            lines.add(m.path().value() + ":" + m.lineNumber() + ":" + m.content());
          }
          if (grepResult.limited()) {
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
}
