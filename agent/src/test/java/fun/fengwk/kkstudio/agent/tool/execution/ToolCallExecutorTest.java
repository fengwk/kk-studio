package fun.fengwk.kkstudio.agent.tool.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.ScheduledTask;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.tool.NoopToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.Tool;
import fun.fengwk.kkstudio.agent.tool.ToolCallRequest;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandler;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.ToolRegistration;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.agent.AgentScheduler;
import fun.fengwk.kkstudio.agent.ScheduledTask;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContentType;
import fun.fengwk.kkstudio.agent.tool.NoopToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.Tool;
import fun.fengwk.kkstudio.agent.tool.ToolCallRequest;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandle;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandler;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import fun.fengwk.kkstudio.agent.tool.ToolRegistration;
import fun.fengwk.kkstudio.agent.tool.schema.ToolParamsSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * ToolCallExecutor 的工具生命周期边界测试。
 *
 * @author fengwk
 */
public class ToolCallExecutorTest {

  /** 校验正常 partial 与 complete 会按顺序回流，且 timeout=0 不注册定时任务。 */
  @Test
  public void testExecuteForwardsPartialAndComplete() {
    CapturingScheduler scheduler = new CapturingScheduler();
    ToolCallExecutor executor = new ToolCallExecutor(new DirectExecutorService(), scheduler);
    RecordingListener listener = new RecordingListener();

    executor.execute(
        registration(
            "echo",
            (request, handler) -> {
              handler.onPartial(List.of(textDelta(0, "o")));
              handler.onComplete(List.of(textContent("ok")));
              return new NoopToolExecutionHandle();
            }),
        request(),
        listener);

    assertEquals(1, listener.partials.size());
    assertEquals(1, listener.completes.size());
    assertEquals(0, listener.errors.size());
    assertEquals(0, scheduler.scheduledCount);
  }

  /** 校验 asyncExecute 返回 null 会作为工具契约错误回流。 */
  @Test
  public void testNullHandleBecomesError() {
    ToolCallExecutor executor =
        new ToolCallExecutor(new DirectExecutorService(), new CapturingScheduler());
    RecordingListener listener = new RecordingListener();

    executor.execute(registration("echo", (request, handler) -> null), request(), listener);

    assertEquals(1, listener.errors.size());
    assertEquals("tool asyncExecute returned null handle", listener.errors.get(0).getMessage());
  }

  /** 校验 timeout 会回流 ToolTimeoutException 并取消下游工具句柄。 */
  @Test
  public void testTimeoutCancelsDelegateHandle() {
    CapturingScheduler scheduler = new CapturingScheduler();
    ToolCallExecutor executor = new ToolCallExecutor(new DirectExecutorService(), scheduler);
    RecordingListener listener = new RecordingListener();
    NoopToolExecutionHandle delegate = new NoopToolExecutionHandle();

    executor.execute(
        registration(
            "echo",
            new Tool() {
              @Override
              public ToolExecutionHandle asyncExecute(
                  ToolCallRequest request, ToolExecutionHandler handler) {
                return delegate;
              }

              @Override
              public long timeoutSeconds() {
                return 3L;
              }
            }),
        request(),
        listener);

    assertFalse(delegate.isCancelled());
    scheduler.fire();

    assertTrue(delegate.isCancelled());
    assertEquals(1, listener.errors.size());
    ToolTimeoutException error =
        assertInstanceOf(ToolTimeoutException.class, listener.errors.get(0));
    assertEquals("call_1", error.getToolCallId());
    assertEquals("echo", error.getToolName());
    assertEquals(3L, error.getTimeoutSeconds());
    assertEquals("tool timeout after 3 seconds: echo", error.getMessage());
  }

  /** 校验启动阶段尚未执行时 timeout 会取消 worker future，避免后续再运行工具逻辑。 */
  @Test
  public void testTimeoutCancelsPendingWorkerFuture() {
    CapturingScheduler scheduler = new CapturingScheduler();
    ManualExecutorService worker = new ManualExecutorService();
    ToolCallExecutor executor = new ToolCallExecutor(worker, scheduler);
    RecordingListener listener = new RecordingListener();
    CountingTool tool = new CountingTool();

    executor.execute(registration("echo", tool), request(), listener);
    scheduler.fire();
    worker.runPending();

    assertEquals(0, tool.invocations);
    assertEquals(1, listener.errors.size());
  }

  /** 校验非法 partial 结构会使当前工具调用进入错误终态。 */
  @Test
  public void testInvalidPartialBecomesError() {
    ToolCallExecutor executor =
        new ToolCallExecutor(new DirectExecutorService(), new CapturingScheduler());
    RecordingListener listener = new RecordingListener();

    executor.execute(
        registration(
            "echo",
            (request, handler) -> {
              handler.onPartial(List.of(textDelta(-1, "bad")));
              return new NoopToolExecutionHandle();
            }),
        request(),
        listener);

    assertEquals(0, listener.partials.size());
    assertEquals(1, listener.errors.size());
    assertTrue(listener.errors.get(0).getMessage().contains("negative_tool_content_delta_index"));
  }

  /** 校验 partial 与 complete 在同一槽位上的类型冲突会进入错误终态。 */
  @Test
  public void testCompleteTypeConflictBecomesError() {
    ToolCallExecutor executor =
        new ToolCallExecutor(new DirectExecutorService(), new CapturingScheduler());
    RecordingListener listener = new RecordingListener();

    executor.execute(
        registration(
            "echo",
            (request, handler) -> {
              handler.onPartial(List.of(textDelta(0, "text")));
              ToolContent image = new ToolContent();
              image.setType(ToolContentType.image);
              image.setData("img");
              image.setMime("image/png");
              handler.onComplete(List.of(image));
              return new NoopToolExecutionHandle();
            }),
        request(),
        listener);

    assertEquals(1, listener.partials.size());
    assertEquals(0, listener.completes.size());
    assertEquals(1, listener.errors.size());
    assertTrue(listener.errors.get(0).getMessage().contains("tool_content_type_conflict"));
  }

  /** 校验终态后的重复 complete 会被忽略。 */
  @Test
  public void testDuplicateCompleteIgnored() {
    ToolCallExecutor executor =
        new ToolCallExecutor(new DirectExecutorService(), new CapturingScheduler());
    RecordingListener listener = new RecordingListener();

    executor.execute(
        registration(
            "echo",
            (request, handler) -> {
              handler.onComplete(List.of(textContent("ok")));
              handler.onComplete(List.of(textContent("late")));
              return new NoopToolExecutionHandle();
            }),
        request(),
        listener);

    assertEquals(1, listener.completes.size());
    assertEquals("ok", listener.completes.get(0).get(0).getText());
    assertEquals(0, listener.errors.size());
  }

  /** 校验负超时、空依赖和 executor submit 失败的边界行为。 */
  @Test
  public void testExecutorRejectsInvalidConfigurationOrSubmitFailure() {
    assertThrows(
        IllegalArgumentException.class, () -> new ToolCallExecutor(null, new CapturingScheduler()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCallExecutor(new DirectExecutorService(), null));

    ToolCallExecutor executor =
        new ToolCallExecutor(new RejectingExecutorService(), new CapturingScheduler());
    RecordingListener listener = new RecordingListener();
    executor.execute(registration("echo", noopTool()), request(), listener);
    assertEquals(1, listener.errors.size());
    assertInstanceOf(RejectedExecutionException.class, listener.errors.get(0));

    ToolCallExecutor negativeTimeoutExecutor =
        new ToolCallExecutor(new DirectExecutorService(), new CapturingScheduler());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            negativeTimeoutExecutor.execute(
                registration("echo", negativeTimeoutTool()), request(), new RecordingListener()));
  }

  /** 校验 execute(...) 拒绝缺失核心参数。 */
  @Test
  public void testExecuteRejectsNullArguments() {
    ToolCallExecutor executor =
        new ToolCallExecutor(new DirectExecutorService(), new CapturingScheduler());
    ToolRegistration registration = registration("echo", noopTool());
    ToolCallRequest request = request();
    RecordingListener listener = new RecordingListener();

    assertThrows(IllegalArgumentException.class, () -> executor.execute(null, request, listener));
    assertThrows(
        IllegalArgumentException.class, () -> executor.execute(registration, null, listener));
    assertThrows(
        IllegalArgumentException.class, () -> executor.execute(registration, request, null));
  }

  /** 校验工具同步抛异常会直接回流 error。 */
  @Test
  public void testAsyncExecuteThrowBecomesError() {
    ToolCallExecutor executor =
        new ToolCallExecutor(new DirectExecutorService(), new CapturingScheduler());
    RecordingListener listener = new RecordingListener();

    executor.execute(
        registration(
            "echo",
            (request, handler) -> {
              throw new IllegalStateException("boom");
            }),
        request(),
        listener);

    assertEquals(1, listener.errors.size());
    assertEquals("boom", listener.errors.get(0).getMessage());
  }

  private ToolRegistration registration(String name, Tool tool) {
    ToolInfo toolInfo =
        ToolInfo.builder()
            .name(name)
            .description(name)
            .inputSchema(ToolParamsSchema.builder().build())
            .build();
    return new ToolRegistration(name, toolInfo, tool);
  }

  private ToolCallRequest request() {
    return new ToolCallRequest("call_1", "echo", "{}");
  }

  private IndexedToolContentDelta textDelta(int index, String text) {
    ToolContentDelta contentDelta = new ToolContentDelta();
    contentDelta.setType(ToolContentType.text);
    contentDelta.setText(text);
    IndexedToolContentDelta indexed = new IndexedToolContentDelta();
    indexed.setIndex(index);
    indexed.setContentDelta(contentDelta);
    return indexed;
  }

  private ToolContent textContent(String text) {
    ToolContent content = new ToolContent();
    content.setType(ToolContentType.text);
    content.setText(text);
    return content;
  }

  private Tool noopTool() {
    return (request, handler) -> new NoopToolExecutionHandle();
  }

  private Tool negativeTimeoutTool() {
    return new Tool() {
      @Override
      public ToolExecutionHandle asyncExecute(
          ToolCallRequest request, ToolExecutionHandler handler) {
        return new NoopToolExecutionHandle();
      }

      @Override
      public long timeoutSeconds() {
        return -1L;
      }
    };
  }

  private static class RecordingListener implements ToolExecutionListener {
    private final List<List<IndexedToolContentDelta>> partials = new ArrayList<>();
    private final List<List<ToolContent>> completes = new ArrayList<>();
    private final List<Throwable> errors = new ArrayList<>();

    @Override
    public void onPartial(List<IndexedToolContentDelta> partial) {
      partials.add(partial);
    }

    @Override
    public void onComplete(List<ToolContent> result) {
      completes.add(result);
    }

    @Override
    public void onError(Throwable error) {
      errors.add(error);
    }
  }

  private static class CapturingScheduler implements AgentScheduler {
    private Runnable task;
    private boolean cancelled;
    private int scheduledCount;

    @Override
    public ScheduledTask schedule(Duration delay, Runnable task) {
      this.task = task;
      this.scheduledCount++;
      return () -> cancelled = true;
    }

    private void fire() {
      if (!cancelled && task != null) {
        task.run();
      }
    }
  }

  private static class DirectExecutorService extends AbstractExecutorService {
    private boolean shutdown;

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }

    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }

  private static class ManualExecutorService extends AbstractExecutorService {
    private Runnable pending;
    private boolean shutdown;

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }

    @Override
    public void execute(Runnable command) {
      pending = command;
    }

    private void runPending() {
      Runnable command = pending;
      pending = null;
      if (command instanceof FutureTask<?> futureTask && futureTask.isCancelled()) {
        return;
      }
      if (command != null) {
        command.run();
      }
    }
  }

  private static class RejectingExecutorService extends AbstractExecutorService {
    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return false;
    }

    @Override
    public void execute(Runnable command) {
      throw new RejectedExecutionException("rejected");
    }
  }

  private static class CountingTool implements Tool {
    private int invocations;

    @Override
    public ToolExecutionHandle asyncExecute(ToolCallRequest request, ToolExecutionHandler handler) {
      invocations++;
      return new NoopToolExecutionHandle();
    }

    @Override
    public long timeoutSeconds() {
      return 1L;
    }
  }
}
