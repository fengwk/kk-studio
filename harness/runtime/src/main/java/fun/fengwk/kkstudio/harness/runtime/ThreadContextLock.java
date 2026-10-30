package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
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
 * <p>从调用方已锁定的 Thread 出发，加载 root-to-head {@link EntryPath}，经 {@link AgentDriver#lockState} 取得中立的
 * Runtime 视图，并仅在当前 Assistant head 上加载/锁定 Tool siblings；随后在已锁定的行上运行纯 {@link
 * ThreadContextClassifier}。公共工具操作使用 {@link #loadTools(HarnessStore.Transaction, ThreadState)}，不读取
 * Driver。
 */
final class ThreadContextLock {

  private static final ThreadContextClassifier CLASSIFIER = new ThreadContextClassifier();

  private ThreadContextLock() {}

  static LockedThreadContext load(
      HarnessStore.Transaction tx, ThreadState thread, AgentDriver driver) {
    return load(tx, thread, tx.entries().loadPath(thread.headEntryId()), driver);
  }

  static LockedThreadContext load(
      HarnessStore.Transaction tx, ThreadState thread, EntryPath path, AgentDriver driver) {
    Objects.requireNonNull(driver, "driver");
    LockedDriverState driverState = driver.lockState(tx, thread, path);
    List<ToolInvocation> siblings = List.of();
    if (path.openTurnStart().isPresent() && assistantHead(path)) {
      siblings =
          tx.tools().lockByAssistantEntry(path.head().id()).stream()
              .filter(
                  sibling ->
                      sibling.threadId().equals(thread.id())
                          && sibling
                              .turnStartEntryId()
                              .equals(path.openTurnStart().orElseThrow().id())
                          && sibling.assistantEntryId().equals(path.head().id()))
              .toList();
    }
    ThreadContext context = CLASSIFIER.classify(thread, path, driverState, siblings);
    return new LockedThreadContext(path, context, driverState);
  }

  /** 公共 Tool 分类：只加载并锁定 Thread 与 Tool siblings，不读取 Driver。 */
  static LockedThreadContext loadTools(HarnessStore.Transaction tx, ThreadState thread) {
    EntryPath path = tx.entries().loadPath(thread.headEntryId());
    List<ToolInvocation> siblings =
        path.openTurnStart().isPresent() && assistantHead(path)
            ? tx.tools().lockByAssistantEntry(path.head().id()).stream()
                .filter(
                    sibling ->
                        sibling.threadId().equals(thread.id())
                            && sibling
                                .turnStartEntryId()
                                .equals(path.openTurnStart().orElseThrow().id())
                            && sibling.assistantEntryId().equals(path.head().id()))
                .toList()
            : List.of();
    ThreadContext context = CLASSIFIER.classify(thread, path, NoDriverState.INSTANCE, siblings);
    return new LockedThreadContext(path, context, NoDriverState.INSTANCE);
  }

  private static boolean assistantHead(EntryPath path) {
    return path.head().payload() instanceof MessagePayload message
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
