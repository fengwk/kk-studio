package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
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
}
