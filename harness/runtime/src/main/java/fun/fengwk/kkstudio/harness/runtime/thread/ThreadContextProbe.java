package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.List;
import java.util.Objects;

/**
 * 无锁 Invocation 读取与 {@link ThreadContextClassifier} 组合探测器。
 *
 * <p>Driver 状态完全经 {@link AgentDriver#readState} 获取；Runtime 不读取或强转 Driver 私有类型。分类只使用当前 Entry 状态。Tool
 * siblings 仅在当前 head 为 ASSISTANT Message 时查找，归属由公共字段校验。
 */
public final class ThreadContextProbe {

  private final AgentDriver driver;
  private final ThreadContextClassifier classifier = new ThreadContextClassifier();

  public ThreadContextProbe(AgentDriver driver) {
    this.driver = Objects.requireNonNull(driver, "driver");
  }

  public ThreadContext probe(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState state) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(state, "state");
    LockedDriverState driverState = driver.readState(tx, thread, state);
    List<ToolInvocation> siblings = List.of();
    if (state.openTurnStart().isPresent() && assistantHead(state)) {
      siblings =
          tx.tools().loadByAssistantEntry(state.head().id()).stream()
              .filter(
                  sibling ->
                      sibling.threadId().equals(thread.id())
                          && sibling
                              .turnStartEntryId()
                              .equals(state.openTurnStart().orElseThrow().id())
                          && sibling.assistantEntryId().equals(state.head().id()))
              .toList();
    }
    return classifier.classify(thread, state, driverState, siblings);
  }

  public ThreadContext probe(HarnessStore.Transaction tx, ThreadState thread) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    return probe(tx, thread, tx.entries().loadCurrent(thread.headEntryId()));
  }

  private static boolean assistantHead(EntryCurrentState state) {
    return state.head().payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT;
  }
}
