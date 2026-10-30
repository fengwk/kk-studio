package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.List;
import java.util.Objects;

/**
 * 无锁 Invocation 读取与 {@link ThreadContextClassifier} 组合探测器。
 *
 * <p>Driver 状态完全经 {@link AgentDriver#readState} 获取；Runtime 不读取或强转 Driver 私有类型。Tool siblings 仅在当前
 * head 为 ASSISTANT Message 时查找，归属由公共字段校验。
 */
public final class ThreadContextProbe {

  private final AgentDriver driver;
  private final ThreadContextClassifier classifier = new ThreadContextClassifier();

  public ThreadContextProbe(AgentDriver driver) {
    this.driver = Objects.requireNonNull(driver, "driver");
  }

  public ThreadContext probe(HarnessStore.Transaction tx, ThreadState thread, EntryPath path) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(path, "path");
    LockedDriverState driverState = driver.readState(tx, thread, path);
    List<ToolInvocation> siblings = List.of();
    if (path.openTurnStart().isPresent() && assistantHead(path)) {
      siblings =
          tx.tools().loadByAssistantEntry(path.head().id()).stream()
              .filter(
                  sibling ->
                      sibling.threadId().equals(thread.id())
                          && sibling
                              .turnStartEntryId()
                              .equals(path.openTurnStart().orElseThrow().id())
                          && sibling.assistantEntryId().equals(path.head().id()))
              .toList();
    }
    return classifier.classify(thread, path, driverState, siblings);
  }

  public ThreadContext probe(HarnessStore.Transaction tx, ThreadState thread) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    return probe(tx, thread, tx.entries().loadPath(thread.headEntryId()));
  }

  private static boolean assistantHead(EntryPath path) {
    return path.head().payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT;
  }
}
