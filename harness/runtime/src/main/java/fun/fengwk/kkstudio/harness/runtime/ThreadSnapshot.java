package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.Objects;

/**
 * 一致性加锁下的一个 Thread 的不可变投影：durable {@link ThreadState}、当前 root-to-head {@link EntryPath}、已入队
 * Commands（不可变 list），以及仅与当前 live context 分类器匹配的 {@link ModelInvocation} / Tool siblings / 尚未物化的失败
 * attempts。
 *
 * <p>IDLE_OR_HISTORICAL 与 CONTINUATION_DUE snapshot 不暴露 Model 与 tools；Model context 仅暴露 Model；Tool
 * context 暴露 Model 与全部 Tool siblings；只有 Model context 会附带尚未物化为 Entry 的失败 attempts。
 */
public record ThreadSnapshot(
    ThreadState thread,
    EntryPath entryPath,
    List<ThreadCommand> queuedCommands,
    ModelInvocation model,
    List<ToolInvocation> toolSiblings,
    List<ModelAttemptFailureProjection> modelAttemptFailures) {

  public ThreadSnapshot {
    thread = Objects.requireNonNull(thread, "thread");
    entryPath = Objects.requireNonNull(entryPath, "entryPath");
    queuedCommands = List.copyOf(Objects.requireNonNull(queuedCommands, "queuedCommands"));
    toolSiblings = List.copyOf(Objects.requireNonNull(toolSiblings, "toolSiblings"));
    modelAttemptFailures =
        List.copyOf(Objects.requireNonNull(modelAttemptFailures, "modelAttemptFailures"));
  }

  /** 递归生命周期确定是否空闲；本地适用上下文保留审批、终态待物化和续写等细分阶段。 */
  public ThreadRuntimeStatus runtimeStatus() {
    return switch (thread.status()) {
      case WAITING_CHILDREN -> ThreadRuntimeStatus.WAITING_CHILDREN;
      case IDLE -> ThreadRuntimeStatus.IDLE;
      case ACTIVE -> {
        ThreadRuntimeStatus local =
            ThreadRuntimeStatus.from(
                new ThreadContextClassifier().classify(thread, entryPath, model, toolSiblings));
        yield local == ThreadRuntimeStatus.IDLE ? ThreadRuntimeStatus.QUEUED : local;
      }
    };
  }
}
