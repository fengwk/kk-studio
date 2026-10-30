package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.testing.FakeAgentDriver;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Instant;
import java.util.UUID;

/** {@link ThreadContextProbe} Store 组合读取测试。 */
class ThreadContextProbeTest {

  private static final UUID SESSION_ID = id(100);
  private static final UUID THREAD_ID = id(101);
  private static final UUID ROOT_ID = id(1);
  private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  @Test
  void probeWithoutExplicitPathLoadsTheLockedThreadHead() {
    // 两参数入口必须从同一 transaction 加载 head path，再交给共享分类器。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    ThreadState thread =
        new ThreadState(
            THREAD_ID,
            SESSION_ID,
            null,
            ROOT_ID,
            CREATION_REQUEST_HASH,
            "main",
            THREAD_ID,
            false,
            SpawnTrigger.USER,
            ThreadConstraints.NONE,
            ThreadExecutionControl.RUNNABLE,
            0L,
            1,
            0,
            NOW,
            NOW);
    store.transaction(
        tx -> {
          tx.insertSession(new Session(SESSION_ID, "session-" + SESSION_ID, NOW));
          tx.insertEntry(
              new Entry(
                  ROOT_ID,
                  SESSION_ID,
                  null,
                  new RootPayload(
                      new BranchSettings("agent", ModelSelection.model("model", "variant"), null)),
                  NOW));
          tx.insertThread(thread);
          return null;
        });

    ThreadContext context =
        store.transaction(
            tx ->
                new ThreadContextProbe(new FakeAgentDriver())
                    .probe(tx, tx.lockThread(THREAD_ID).orElseThrow()));

    assertInstanceOf(ThreadContext.IdleOrHistorical.class, context);
  }

  @Test
  void probeLoadsOwnedToolSiblingsOnlyForAssistantHead() {
    // assistant head 必须只装载同 Thread、同 turn、同 assistant 的 sibling，才能进入 ToolActive 分类。
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.ToolBaseline baseline = seedToolBaseline(store);

    ThreadContext context =
        store.transaction(
            tx ->
                new ThreadContextProbe(new FakeAgentDriver())
                    .probe(tx, tx.lockThread(baseline.threadId()).orElseThrow()));

    assertInstanceOf(ThreadContext.ToolActive.class, context);
  }
}
