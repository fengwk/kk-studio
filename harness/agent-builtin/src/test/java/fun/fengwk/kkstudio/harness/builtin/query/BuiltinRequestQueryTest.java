package fun.fengwk.kkstudio.harness.builtin.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builtin 冻结请求查询：只返回当前 open Turn 上归属于该 Thread 的 Builtin generation。 */
class BuiltinRequestQueryTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void returnsOnlyTheCurrentThreadOpenTurnRequest() {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    Seed owner = seed(store, true);
    Seed other = seed(store, false);
    BuiltinRequestQuery query = new BuiltinRequestQuery(store, store);

    BuiltinRequestView view = query.findCurrent(owner.threadId()).orElseThrow();

    assertEquals(owner.modelId(), view.generationId());
    assertEquals(0, view.attempt());
    assertEquals("frozen request", view.requestSpec().systemInstruction());
    // 另一个 Thread 的 open Turn 没有 Builtin generation：绝不跨 Thread 泄漏。
    assertTrue(query.findCurrent(other.threadId()).isEmpty());
  }

  @Test
  void returnsEmptyWithoutThreadOpenTurnOrInvocation() {
    InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
    Seed noModel = seed(store, false);
    BuiltinRequestQuery query = new BuiltinRequestQuery(store, store);

    assertTrue(query.findCurrent(new UUID(0L, 999L)).isEmpty());
    assertTrue(query.findCurrent(noModel.threadId()).isEmpty());
  }

  private static Seed seed(InMemoryBuiltinHarnessStore store, boolean withModel) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          BranchSettings settings =
              new BranchSettings("agent", new ModelSelection("model", "v1"), null);
          tx.sessions().insert(new Session(sessionId, "session", NOW));
          tx.entries()
              .insert(new Entry(rootEntryId, sessionId, null, new RootPayload(settings), NOW));
          tx.entries()
              .insert(
                  new Entry(
                      turnStartEntryId,
                      sessionId,
                      rootEntryId,
                      new TurnStartPayload(
                          TurnStartReason.INPUT,
                          settings,
                          threadId,
                          100_000,
                          16_384,
                          null,
                          null,
                          null),
                      NOW));
          tx.threads()
              .insert(
                  new ThreadState(
                      threadId,
                      sessionId,
                      null,
                      turnStartEntryId,
                      CREATION_REQUEST_HASH,
                      "main",
                      threadId,
                      false,
                      SpawnTrigger.USER,
                      ThreadConstraints.NONE,
                      ThreadExecutionControl.RUNNABLE,
                      0L,
                      1L,
                      0L,
                      NOW,
                      NOW));
          UUID modelId = null;
          if (withModel) {
            modelId = tx.nextId();
            store
                .in(tx)
                .insertModelInvocation(
                    new ModelInvocation(
                        modelId,
                        threadId,
                        turnStartEntryId,
                        turnStartEntryId,
                        requestSpec(),
                        ModelInvocationStatus.READY,
                        0,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        NOW,
                        NOW));
          }
          return new Seed(threadId, modelId);
        });
  }

  private static ModelRequestSpec requestSpec() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        new ModelDescriptor(
            "provider", "model", "wire-model", Set.of(ModelInputModality.TEXT), true, false),
        new ModelVariant("v1"),
        512,
        "frozen request",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private record Seed(UUID threadId, UUID modelId) {}
}
