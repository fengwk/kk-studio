package fun.fengwk.kkstudio.harness.runtime.thread;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;

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
            THREAD_ID, SESSION_ID, ROOT_ID, CREATION_REQUEST_HASH, "main", false, 1, 0, NOW, NOW);
    store.transaction(
        tx -> {
          tx.insertSession(new Session(SESSION_ID, "session-" + SESSION_ID, NOW));
          tx.insertEntry(
              new Entry(
                  ROOT_ID,
                  SESSION_ID,
                  null,
                  new RootPayload(
                      new BranchSettings(
                          "agent", new ModelSelection("provider", "model", "variant"))),
                  NOW));
          tx.insertThread(thread);
          return null;
        });

    ThreadContext context =
        store.transaction(
            tx -> new ThreadContextProbe().probe(tx, tx.lockThread(THREAD_ID).orElseThrow()));

    assertInstanceOf(ThreadContext.IdleOrHistorical.class, context);
  }
}
