package fun.fengwk.kkstudio.harness.builtin.query;

import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builtin debug 专属查询：在同一 Runtime 事务内锁定 Thread，读取当前 open Turn 的 Builtin generation 冻结请求。
 *
 * <p>查询只接受归属于该 Thread、该 open Turn 且 request head 在当前路径上的 Invocation；其它 Driver 或没有任何 Builtin
 * generation 时返回 empty。公共 ThreadSnapshot 不再携带私有 request spec。
 */
public final class BuiltinRequestQuery {

  private final HarnessStore store;
  private final BuiltinStore builtinStore;

  public BuiltinRequestQuery(HarnessStore store, BuiltinStore builtinStore) {
    this.store = Objects.requireNonNull(store, "store");
    this.builtinStore = Objects.requireNonNull(builtinStore, "builtinStore");
  }

  public Optional<BuiltinRequestView> findCurrent(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElse(null);
          if (thread == null) {
            return Optional.empty();
          }
          EntryPath path = tx.loadEntryPath(thread.headEntryId());
          Entry turn = path.openTurnStart().orElse(null);
          if (turn == null
              || !(turn.payload() instanceof TurnStartPayload start)
              || !thread.id().equals(start.ownerThreadId())) {
            return Optional.empty();
          }
          Optional<ModelInvocation> found =
              builtinStore.in(tx).findModelInvocationByTurn(thread.id(), turn.id());
          if (found.isEmpty()) {
            return Optional.empty();
          }
          ModelInvocation model = found.get();
          if (!model.threadId().equals(thread.id())
              || !model.turnStartEntryId().equals(turn.id())
              || path.entries().stream()
                  .noneMatch(entry -> entry.id().equals(model.requestHeadEntryId()))) {
            throw new IllegalStateException(
                "builtin generation does not belong to thread "
                    + thread.id()
                    + " turn "
                    + turn.id());
          }
          return Optional.of(
              new BuiltinRequestView(model.id(), model.attempt(), model.requestSpec()));
        });
  }
}
