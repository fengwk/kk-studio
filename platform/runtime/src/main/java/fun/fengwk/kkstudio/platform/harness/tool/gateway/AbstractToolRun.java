package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import fun.fengwk.kkstudio.harness.api.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.ToolSuccess;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 本地工具执行与远端重挂接共用的执行模板：partial 守卫、执行线程登记、终态装配与 close-once。
 *
 * <p>{@link #execute} 在调用方的执行线程上阻塞执行子类提供的执行体，中断由子类映射为取消。工具上报非法 partial 时中断执行线程并以确定性失败
 * 收敛：执行已开始，因此只声明结果未交付且不可确认，不能声称未执行。
 *
 * <p>{@link #close()} 只释放子类持有的资源；调用方保证它在终态决议提交之后、{@link #execute} 返回（或抛出）之后才被调用。
 */
abstract class AbstractToolRun implements ToolGateway.Run {

  private final ToolResultFinalizer finalizer;
  private final String toolName;
  private final String expectedCallId;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<ToolInvocationError> rejectedPartial = new AtomicReference<>();
  private final Object executionLock = new Object();
  private Thread executingThread;

  AbstractToolRun(ToolResultFinalizer finalizer, String toolName, String expectedCallId) {
    this.finalizer = Objects.requireNonNull(finalizer, "finalizer");
    this.toolName = Objects.requireNonNull(toolName, "toolName");
    this.expectedCallId = Objects.requireNonNull(expectedCallId, "expectedCallId");
  }

  /** 本次调用的模型 call id：远端结果关联与终态校验都以此为准。 */
  final String expectedCallId() {
    return expectedCallId;
  }

  /** 模型可见工具名：错误分类与 finalize 采用同一身份。 */
  final String toolName() {
    return toolName;
  }

  @Override
  public final ToolGateway.Completion execute(Consumer<ToolResult> progress) {
    Objects.requireNonNull(progress, "progress");
    Consumer<ToolResult> guarded = guard(progress);
    synchronized (executionLock) {
      executingThread = Thread.currentThread();
    }
    ToolGateway.Completion completion;
    try {
      completion = invoke(guarded);
    } finally {
      synchronized (executionLock) {
        executingThread = null;
      }
    }
    ToolInvocationError invalid = rejectedPartial.get();
    return invalid == null ? completion : ToolErrorClassifier.fail(invalid);
  }

  @Override
  public final void close() {
    if (closed.compareAndSet(false, true)) {
      release();
    }
  }

  /** 执行体：收到已通过 partial 守卫的进度回调。 */
  protected abstract ToolGateway.Completion invoke(Consumer<ToolResult> guarded);

  /** 释放本次 Run 独占的资源；恰好调用一次。 */
  protected abstract void release();

  /**
   * 共享终态装配：校验 call id、解析 durable effects、按大小限制外部化内容并映射为 {@link ToolGateway.Completion}。
   *
   * <p>effects 解析在大小限制之前、call id 校验之后执行，违反 Contributor 契约时只能声明结果未确认。
   */
  protected final ToolGateway.Completion completion(
      ToolResult result, Supplier<ToolEffectBatch> effects) {
    if (result == null) {
      return ToolErrorClassifier.fail(
          ToolErrorClassifier.invalidResult("terminal result must not be null"));
    }
    if (!result.toolCallId().equals(expectedCallId)) {
      return ToolErrorClassifier.fail(
          ToolErrorClassifier.invalidResult(
              "terminal result toolCallId does not match the request call"));
    }
    ToolEffectBatch batch;
    try {
      batch = effects.get();
    } catch (RuntimeException invalid) {
      return ToolErrorClassifier.fail(
          ToolErrorClassifier.contributorContractViolation(
              invalid, "Tool outcome returned invalid custom entries"));
    }
    ToolResultFinalizer.Outcome finalized;
    try {
      finalized = finalizer.finalizeResult(toolName, result);
    } catch (RuntimeException failure) {
      return ToolErrorClassifier.unknown(toolName, failure, "tool result finalization failed");
    }
    return switch (finalized) {
      case ToolResultFinalizer.Outcome.Success success -> new ToolGateway.Succeeded(
          new ToolSuccess(success.result(), batch));
      case ToolResultFinalizer.Outcome.Failed failed -> ToolErrorClassifier.fail(failed.error());
      case ToolResultFinalizer.Outcome.StoreFailed storeFailed -> new ToolGateway.Unknown(
          storeFailed.error());
    };
  }

  /** 校验 partial；非法即记录并中断执行线程（只在执行体内部投递），由 {@link #execute} 收敛为确定性失败。 */
  private Consumer<ToolResult> guard(Consumer<ToolResult> progress) {
    return partial -> {
      ToolInvocationError invalid = ToolErrorClassifier.validatePartial(partial, expectedCallId);
      if (invalid == null) {
        progress.accept(partial);
        return;
      }
      if (rejectedPartial.compareAndSet(null, invalid)) {
        synchronized (executionLock) {
          if (executingThread != null) {
            executingThread.interrupt();
          }
        }
      }
    };
  }
}
