package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread domain contracts — fencing / input order / event cursor. */
class ThreadContractsTest {
  private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

  @Test
  void agentThreadRejectsInvalidIds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AgentThread(0, 1, 1, null, "{}", false, 0, null, null, 0, NOW, NOW));
  }

  @Test
  void inMemoryTokenFencingIsSingleFlight() {
    InMemoryThreadStore store = new InMemoryThreadStore();
    store.create(new AgentThread(1, 10, 100, 1L, "{}", false, 0, null, null, 0, NOW, NOW));
    Optional<AgentThread> a = store.tryAcquire(1, "token-a", NOW, Duration.ofSeconds(30));
    Optional<AgentThread> b = store.tryAcquire(1, "token-b", NOW, Duration.ofSeconds(30));
    assertTrue(a.isPresent());
    assertFalse(b.isPresent());
    assertTrue(store.release(1, "token-a", NOW.plusSeconds(1)));
    assertTrue(
        store.tryAcquire(1, "token-b", NOW.plusSeconds(2), Duration.ofSeconds(30)).isPresent());
  }

  @Test
  void expiredTokenCanBeReclaimed() {
    InMemoryThreadStore store = new InMemoryThreadStore();
    store.create(new AgentThread(1, 10, 100, 1L, "{}", false, 0, null, null, 0, NOW, NOW));
    store.tryAcquire(1, "token-a", NOW, Duration.ofSeconds(5));
    Optional<AgentThread> reclaimed =
        store.tryAcquire(1, "token-b", NOW.plusSeconds(6), Duration.ofSeconds(5));
    assertTrue(reclaimed.isPresent());
    assertEquals("token-b", reclaimed.orElseThrow().processorToken());
  }

  @Test
  void inputSequenceAndIdempotentClientMessage() {
    InMemoryThreadStore store = new InMemoryThreadStore();
    store.create(new AgentThread(1, 10, 100, 1L, "{}", false, 0, null, null, 0, NOW, NOW));
    long seq1 = store.allocateInputSequence(1, NOW);
    long seq2 = store.allocateInputSequence(1, NOW.plusMillis(1));
    assertEquals(1, seq1);
    assertEquals(2, seq2);
    ThreadInput first =
        new ThreadInput(
            11, 1, seq1, ThreadInputType.USER_MESSAGE, "{\"c\":\"a\"}", "cid-1", null, null, NOW);
    store.insert(first);
    assertTrue(store.findByClientMessageId(1, "cid-1").isPresent());
    assertEquals(1, store.listPending(1).size());
    assertTrue(store.markApplied(11, 200, NOW.plusSeconds(1)));
    assertFalse(store.markApplied(11, 201, NOW.plusSeconds(2)));
    assertTrue(store.listPending(1).isEmpty());
  }

  @Test
  void eventIdsMonotonicAsSseCursor() {
    InMemoryThreadStore store = new InMemoryThreadStore();
    ThreadEvent e1 =
        store.append(1, null, ThreadEventType.THREAD_STARTED, ThreadEventPayloads.of(), NOW);
    ThreadEvent e2 =
        store.append(
            1, 10L, ThreadEventType.ASSISTANT_COMPLETED, ThreadEventPayloads.of("ok", true), NOW);
    assertTrue(e2.id() > e1.id());
    List<ThreadEvent> after = store.listAfter(1, e1.id(), 10);
    assertEquals(1, after.size());
    assertEquals(e2.id(), after.get(0).id());
  }

  /** Minimal in-memory store covering ThreadStore + Input + Event for contract tests. */
  static final class InMemoryThreadStore
      implements ThreadStore, ThreadInputStore, ThreadEventStore {
    private final Map<Long, AgentThread> threads = new ConcurrentHashMap<>();
    private final Map<Long, ThreadInput> inputs = new ConcurrentHashMap<>();
    private final List<ThreadEvent> events = new ArrayList<>();
    private final AtomicLong eventIds = new AtomicLong(1);

    @Override
    public Optional<AgentThread> find(long threadId) {
      return Optional.ofNullable(threads.get(threadId));
    }

    @Override
    public List<AgentThread> listBySession(long sessionId) {
      return threads.values().stream().filter(t -> t.sessionId() == sessionId).toList();
    }

    @Override
    public void create(AgentThread thread) {
      threads.put(thread.id(), thread);
    }

    @Override
    public Optional<AgentThread> tryAcquire(
        long threadId, String processorToken, Instant now, Duration leaseDuration) {
      AgentThread current = threads.get(threadId);
      if (current == null) {
        return Optional.empty();
      }
      if (current.processorToken() != null
          && current.processorUntil() != null
          && current.processorUntil().isAfter(now)) {
        return Optional.empty();
      }
      AgentThread acquired =
          new AgentThread(
              current.id(),
              current.sessionId(),
              current.headEntryId(),
              current.agentDefinitionId(),
              current.runtimeConfigJson(),
              current.yoloEnabled(),
              current.inputSequence(),
              processorToken,
              now.plus(leaseDuration),
              current.version() + 1,
              current.createdAt(),
              now);
      threads.put(threadId, acquired);
      return Optional.of(acquired);
    }

    @Override
    public boolean renew(
        long threadId, String processorToken, Instant now, Duration leaseDuration) {
      AgentThread current = threads.get(threadId);
      if (current == null || !processorToken.equals(current.processorToken())) {
        return false;
      }
      threads.put(
          threadId,
          new AgentThread(
              current.id(),
              current.sessionId(),
              current.headEntryId(),
              current.agentDefinitionId(),
              current.runtimeConfigJson(),
              current.yoloEnabled(),
              current.inputSequence(),
              processorToken,
              now.plus(leaseDuration),
              current.version() + 1,
              current.createdAt(),
              now));
      return true;
    }

    @Override
    public boolean release(long threadId, String processorToken, Instant now) {
      AgentThread current = threads.get(threadId);
      if (current == null || !processorToken.equals(current.processorToken())) {
        return false;
      }
      threads.put(
          threadId,
          new AgentThread(
              current.id(),
              current.sessionId(),
              current.headEntryId(),
              current.agentDefinitionId(),
              current.runtimeConfigJson(),
              current.yoloEnabled(),
              current.inputSequence(),
              null,
              null,
              current.version() + 1,
              current.createdAt(),
              now));
      return true;
    }

    @Override
    public boolean advanceHead(
        long threadId,
        String processorToken,
        long expectedHeadEntryId,
        long newHeadEntryId,
        Instant now) {
      AgentThread current = threads.get(threadId);
      if (current == null
          || !processorToken.equals(current.processorToken())
          || current.headEntryId() != expectedHeadEntryId) {
        return false;
      }
      threads.put(
          threadId,
          new AgentThread(
              current.id(),
              current.sessionId(),
              newHeadEntryId,
              current.agentDefinitionId(),
              current.runtimeConfigJson(),
              current.yoloEnabled(),
              current.inputSequence(),
              current.processorToken(),
              current.processorUntil(),
              current.version() + 1,
              current.createdAt(),
              now));
      return true;
    }

    @Override
    public boolean updateYolo(
        long threadId, String processorToken, boolean yoloEnabled, Instant now) {
      return true;
    }

    @Override
    public boolean updateAgent(
        long threadId,
        String processorToken,
        Long agentDefinitionId,
        String runtimeConfigJson,
        Instant now) {
      return true;
    }

    @Override
    public long allocateInputSequence(long threadId, Instant now) {
      AgentThread current = threads.get(threadId);
      long next = current.inputSequence() + 1;
      threads.put(
          threadId,
          new AgentThread(
              current.id(),
              current.sessionId(),
              current.headEntryId(),
              current.agentDefinitionId(),
              current.runtimeConfigJson(),
              current.yoloEnabled(),
              next,
              current.processorToken(),
              current.processorUntil(),
              current.version() + 1,
              current.createdAt(),
              now));
      return next;
    }

    @Override
    public void insert(ThreadInput input) {
      inputs.put(input.id(), input);
    }

    @Override
    public Optional<ThreadInput> findById(long inputId) {
      return Optional.ofNullable(inputs.get(inputId));
    }

    @Override
    public Optional<ThreadInput> findByClientMessageId(long threadId, String clientMessageId) {
      return inputs.values().stream()
          .filter(i -> i.threadId() == threadId && clientMessageId.equals(i.clientMessageId()))
          .findFirst();
    }

    @Override
    public List<ThreadInput> listByThread(long threadId) {
      return inputs.values().stream().filter(i -> i.threadId() == threadId).toList();
    }

    @Override
    public List<ThreadInput> listPending(long threadId) {
      return inputs.values().stream()
          .filter(i -> i.threadId() == threadId && !i.applied())
          .sorted((a, b) -> Long.compare(a.sequence(), b.sequence()))
          .toList();
    }

    @Override
    public Optional<ThreadInput> findNextPending(long threadId) {
      return listPending(threadId).stream().findFirst();
    }

    @Override
    public boolean markApplied(long inputId, long appliedEntryId, Instant appliedAt) {
      ThreadInput current = inputs.get(inputId);
      if (current == null || current.applied()) {
        return false;
      }
      inputs.put(
          inputId,
          new ThreadInput(
              current.id(),
              current.threadId(),
              current.sequence(),
              current.inputType(),
              current.payloadJson(),
              current.clientMessageId(),
              appliedEntryId,
              appliedAt,
              current.createdAt()));
      return true;
    }

    @Override
    public ThreadEvent append(
        long threadId, Long subjectEntryId, ThreadEventType type, String payloadJson, Instant now) {
      ThreadEvent event =
          new ThreadEvent(
              eventIds.getAndIncrement(), threadId, subjectEntryId, type, payloadJson, now);
      events.add(event);
      return event;
    }

    @Override
    public List<ThreadEvent> listAfter(long threadId, long afterEventId, int limit) {
      return events.stream()
          .filter(e -> e.threadId() == threadId && e.id() > afterEventId)
          .limit(limit)
          .toList();
    }
  }
}
