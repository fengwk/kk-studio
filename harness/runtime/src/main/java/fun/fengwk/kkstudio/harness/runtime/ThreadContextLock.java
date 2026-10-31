package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 小型 HarnessStore transaction 辅助工具，被所有 control-plane 入口共用。
 *
 * <p>从调用方已锁定的 Thread 出发，只加载当前 {@link EntryCurrentState}，经 {@link AgentDriver#lockState} 取得中立的
 * Runtime 视图，并仅在当前 Assistant head 上加载/锁定 Tool siblings；随后在已锁定的行上运行纯 {@link
 * ThreadContextClassifier}。公共工具操作使用 {@link #loadTools(HarnessStore.Transaction, ThreadState)}，不读取
 * Driver，也不加载完整历史。
 */
final class ThreadContextLock {

  private static final ThreadContextClassifier CLASSIFIER = new ThreadContextClassifier();

  private ThreadContextLock() {}

  static LockedThreadContext load(
      HarnessStore.Transaction tx, ThreadState thread, AgentDriver driver) {
    return load(tx, thread, tx.entries().loadCurrent(thread.headEntryId()), driver);
  }

  static LockedThreadContext load(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryCurrentState state,
      AgentDriver driver) {
    Objects.requireNonNull(driver, "driver");
    LockedDriverState driverState = driver.lockState(tx, thread, state);
    List<ToolInvocation> siblings = List.of();
    if (state.openTurnStart().isPresent() && assistantHead(state)) {
      siblings =
          tx.tools().lockByAssistantEntry(state.head().id()).stream()
              .filter(
                  sibling ->
                      sibling.threadId().equals(thread.id())
                          && sibling
                              .turnStartEntryId()
                              .equals(state.openTurnStart().orElseThrow().id())
                          && sibling.assistantEntryId().equals(state.head().id()))
              .toList();
    }
    ThreadContext context = CLASSIFIER.classify(thread, state, driverState, siblings);
    return new LockedThreadContext(state, context, driverState);
  }

  /** 公共 Tool 分类：只加载并锁定 Thread 与 Tool siblings，不读取 Driver，也不加载完整历史。 */
  static LockedThreadContext loadTools(HarnessStore.Transaction tx, ThreadState thread) {
    EntryCurrentState state = tx.entries().loadCurrent(thread.headEntryId());
    List<ToolInvocation> siblings =
        state.openTurnStart().isPresent() && assistantHead(state)
            ? tx.tools().lockByAssistantEntry(state.head().id()).stream()
                .filter(
                    sibling ->
                        sibling.threadId().equals(thread.id())
                            && sibling
                                .turnStartEntryId()
                                .equals(state.openTurnStart().orElseThrow().id())
                            && sibling.assistantEntryId().equals(state.head().id()))
                .toList()
            : List.of();
    ThreadContext context = CLASSIFIER.classify(thread, state, NoDriverState.INSTANCE, siblings);
    return new LockedThreadContext(state, context, NoDriverState.INSTANCE);
  }

  private static boolean assistantHead(EntryCurrentState state) {
    return state.head().payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT;
  }

  /** 无 Driver 的公共工具视图。 */
  private static final class NoDriverState implements LockedDriverState {

    private static final NoDriverState INSTANCE = new NoDriverState();

    @Override
    public DriverActivity activity() {
      return DriverActivity.NONE;
    }

    @Override
    public UUID requestHeadEntryId() {
      return null;
    }

    @Override
    public UUID resultEntryId() {
      return null;
    }

    @Override
    public GenerationProjection projection() {
      return null;
    }
  }
}
