package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptFailurePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptSnapshot;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.util.Objects;
import java.util.UUID;

/** 把 ModelInvocation 的 failedAttempts 按顺序物化为透明历史 Entry。 */
public final class ModelAttemptFailureAppender {

  private ModelAttemptFailureAppender() {}

  /**
   * 将所有失败 attempt 追加到指定 parent；Entry.createdAt 精确使用对应 failure.failedAt。
   *
   * <p>失败记录不改变 ModelInvocation.resultEntryId，也不关闭 Turn。调用方负责在返回 head 后追加真正的
   * Assistant/AssistantError/AssistantAborted 结果和 TURN_END。
   */
  public static UUID append(
      HarnessStore.Transaction tx, UUID sessionId, UUID parentEntryId, ModelInvocation invocation) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(parentEntryId, "parentEntryId");
    Objects.requireNonNull(invocation, "invocation");
    if (invocation.request().compaction() != null) {
      return parentEntryId;
    }
    UUID parent = parentEntryId;
    for (ModelAttemptFailure failure : invocation.failedAttempts()) {
      UUID entryId = tx.nextId();
      tx.insertEntry(new Entry(entryId, sessionId, parent, payload(failure), failure.failedAt()));
      parent = entryId;
    }
    return parent;
  }

  private static ModelAttemptFailurePayload payload(ModelAttemptFailure failure) {
    return new ModelAttemptFailurePayload(
        new ModelAttemptSnapshot(
            failure.attempt(), failure.sequence(), failure.text(), failure.thinking()),
        new AssistantError(failure.error().kind().name(), failure.error().message()),
        failure.retryAt());
  }
}
