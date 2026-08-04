package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, consistently-locked projection of one Thread: the durable {@link ThreadState}, the
 * current root-to-head {@link EntryPath}, the queued Commands (immutable list) and only the
 * classifier-applicable {@link ModelInvocation} / Tool siblings of the current live context.
 *
 * <p>IDLE_OR_HISTORICAL and CONTINUATION_DUE snapshots expose no Model and no tools; Model contexts
 * expose the Model only; Tool contexts expose the Model plus all Tool siblings. No derived status
 * is stored or returned.
 */
public record ThreadSnapshot(
    ThreadState thread,
    EntryPath entryPath,
    List<ThreadCommand> queuedCommands,
    ModelInvocation model,
    List<ToolInvocation> toolSiblings) {

  public ThreadSnapshot {
    thread = Objects.requireNonNull(thread, "thread");
    entryPath = Objects.requireNonNull(entryPath, "entryPath");
    queuedCommands = List.copyOf(Objects.requireNonNull(queuedCommands, "queuedCommands"));
    toolSiblings = List.copyOf(Objects.requireNonNull(toolSiblings, "toolSiblings"));
  }
}
