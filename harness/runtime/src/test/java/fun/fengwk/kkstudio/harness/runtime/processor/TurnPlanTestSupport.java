package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 纯 kernel TurnPlan 测试的 Session/ROOT/Thread 与 Command 基座。 */
final class TurnPlanTestSupport {

  static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  record Baseline(UUID sessionId, UUID rootEntryId, UUID threadId) {}

  private TurnPlanTestSupport() {}

  static Baseline seedBaseline(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, "session-" + sessionId, NOW));
          tx.entries()
              .insert(new Entry(rootEntryId, sessionId, null, new RootPayload(settings()), NOW));
          tx.threads().insert(threadState(threadId, sessionId, rootEntryId));
          return new Baseline(sessionId, rootEntryId, threadId);
        });
  }

  static EntryPath path(InMemoryHarnessStore store, UUID threadId) {
    return store.transaction(
        tx -> tx.entries().loadPath(tx.threads().find(threadId).orElseThrow().headEntryId()));
  }

  static UUID seedCommand(InMemoryHarnessStore store, UUID threadId, ThreadCommandPayload payload) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(threadId).orElseThrow();
          UUID idempotencyKey = tx.nextId();
          long sequence = thread.nextCommandSequence();
          tx.commands()
              .insert(
                  List.of(
                      new ThreadCommand(
                          threadId,
                          sequence,
                          payload,
                          idempotencyKey,
                          ThreadCommandPayloadJsonCodec.requestHash(payload),
                          null,
                          null,
                          null,
                          NOW)));
          tx.threads().update(thread.reserveCommandSequences(1, NOW));
          return idempotencyKey;
        });
  }

  static ThreadCommand command(HarnessStore store, UUID threadId, UUID idempotencyKey) {
    return store
        .transaction(tx -> tx.commands().findByIdempotencyKey(threadId, idempotencyKey))
        .orElseThrow();
  }

  static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static BranchSettings settings() {
    return new BranchSettings("agent", new ExecutionSelection("fake", "{}"), null);
  }

  private static ThreadState threadState(UUID threadId, UUID sessionId, UUID headEntryId) {
    return new ThreadState(
        threadId,
        sessionId,
        null,
        headEntryId,
        CREATION_REQUEST_HASH,
        "main",
        threadId,
        false,
        ThreadKind.USER,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        0L,
        NOW,
        NOW);
  }
}
