package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalConflictException;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalException;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalRecord;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitJournalSnapshot;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.execution.FileExecutionExitJournal;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** 退出确认治理：首次落盘失败保留进程内 proof、不变量慢速核对、Clock 退避、坏文件隔离与删除失败重试。 */
class ExecutionExitConfirmerGovernanceTest {

  @Test
  void firstWriteFailureTouchesNoDatabaseAndRetainsTheOriginalProofTime() {
    Fixture fixture = Fixture.create();
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    journal.failWrites = 1;
    AtomicInteger databaseTransactions = new AtomicInteger();
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(
            countingStore(fixture.store, databaseTransactions, new AtomicInteger()),
            journal,
            fixture.clock);

    assertThrows(
        ExecutionExitJournalException.class,
        () -> confirmer.confirm(fixture.fact, ExecutionExitState.STUDIO_EXITED));
    assertEquals(0, databaseTransactions.get(), "journal write failure must precede any DB access");

    fixture.clock.advance(Duration.ofSeconds(10));
    confirmer.replayPending();

    ExecutionExitFact confirmed = execution(fixture.store, fixture.fact.executionId());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(T0, confirmed.confirmedAt(), "replay must reuse the first in-process proof time");
    assertTrue(journal.delegate.loadAll().records().isEmpty());
  }

  @Test
  void diskSnapshotWriteFailureTouchesNoDatabaseAndRetainsOriginalTime() {
    Fixture fixture = Fixture.create();
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    journal.write(recordFor(fixture.fact));
    journal.failWrites = 1;
    AtomicInteger databaseTransactions = new AtomicInteger();
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(
            countingStore(fixture.store, databaseTransactions, new AtomicInteger()),
            journal,
            fixture.clock);

    confirmer.replayPending();

    assertEquals(
        0, databaseTransactions.get(), "snapshot proof must be rewritten before DB access");
    assertEquals(2, journal.writes());
    assertEquals(1, journal.delegate.loadAll().records().size());

    fixture.clock.advance(Duration.ofSeconds(1));
    confirmer.replayPending();

    ExecutionExitFact confirmed = execution(fixture.store, fixture.fact.executionId());
    assertEquals(1, databaseTransactions.get());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(T0, confirmed.confirmedAt(), "the disk canonical time must be preserved");
    assertTrue(journal.delegate.loadAll().records().isEmpty());
  }

  @Test
  void conflictingCallbackDoesNotPoisonOriginalTransientRetry() {
    Fixture fixture = Fixture.create();
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    AtomicInteger databaseTransactions = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger(1);
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(
            countingStore(fixture.store, databaseTransactions, failures), journal, fixture.clock);

    assertThrows(
        IllegalStateException.class,
        () -> confirmer.confirm(fixture.fact, ExecutionExitState.STUDIO_EXITED));
    assertEquals(1, databaseTransactions.get());
    assertEquals(1, journal.writes());
    assertEquals(1, journal.delegate.loadAll().records().size());

    fixture.clock.advance(Duration.ofSeconds(1));
    assertThrows(
        ExecutionExitJournalConflictException.class,
        () -> confirmer.confirm(fixture.fact, ExecutionExitState.NOT_STARTED));
    assertEquals(1, databaseTransactions.get());
    assertEquals(1, journal.writes(), "a rejected new input must not rewrite the canonical proof");

    confirmer.replayPending();
    ExecutionExitFact confirmed = execution(fixture.store, fixture.fact.executionId());
    assertEquals(2, databaseTransactions.get(), "the original transient failure is due");
    assertEquals(2, journal.writes(), "the cached canonical proof must be written before retry");
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(T0, confirmed.confirmedAt(), "the rejected callback must not replace the proof");
    assertTrue(journal.delegate.loadAll().records().isEmpty());
  }

  @Test
  void rejectedColdCallbackDoesNotBlockTheDurableCanonicalProof() {
    // 冷启动时磁盘已有 canonical proof，错误新回调不得先占住内存队列或污染它的退避状态。
    Fixture fixture = Fixture.create();
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    journal.write(recordFor(fixture.fact));
    AtomicInteger transactions = new AtomicInteger();
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(
            countingStore(fixture.store, transactions, new AtomicInteger()),
            journal,
            fixture.clock);

    assertThrows(
        ExecutionExitJournalConflictException.class,
        () -> confirmer.confirm(fixture.fact, ExecutionExitState.NOT_STARTED));
    assertEquals(0, transactions.get());
    assertEquals(
        ExecutionExitState.STUDIO_EXITED, journal.loadAll().records().getFirst().targetState());

    confirmer.replayPending();
    assertEquals(1, transactions.get(), "a rejected candidate must not delay the durable proof");
    ExecutionExitFact confirmed = execution(fixture.store, fixture.fact.executionId());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(T0, confirmed.confirmedAt());
    assertTrue(journal.loadAll().records().isEmpty());
  }

  @Test
  void invariantFailureUsesSlowRechecksAndCleansAfterDeepDeletion() {
    // 不变量错误不能误归类为瞬态；低频核对仍须识别合法深删，避免永久遗留 journal。
    Fixture fixture = Fixture.create();
    ExecutionExitFact missing = expected(fixture.baseline.threadId(), TestIds.id(25), T0);
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    AtomicInteger transactions = new AtomicInteger();
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(
            countingStore(fixture.store, transactions, new AtomicInteger()),
            journal,
            fixture.clock);

    try (LogCapture logs = LogCapture.attach()) {
      assertThrows(
          IllegalStateException.class,
          () -> confirmer.confirm(missing, ExecutionExitState.STUDIO_EXITED));
      assertEquals(1, transactions.get());
      assertTrue(logs.matching("reason=DATABASE").isEmpty());
      assertEquals(1, logs.matching("reason=FACT_MISSING").size());

      fixture.clock.advance(Duration.ofSeconds(29));
      confirmer.replayPending();
      assertEquals(
          1, transactions.get(), "invariant failure must not retry at transient frequency");
      assertEquals(1, journal.writes());

      fixture.clock.advance(Duration.ofSeconds(1));
      confirmer.replayPending();
      assertEquals(2, transactions.get(), "invariant failure is rechecked after thirty seconds");
      assertEquals(2, journal.writes());
      assertTrue(logs.matching("reason=DATABASE").isEmpty());
      assertEquals(1, logs.matching("reason=FACT_MISSING").size());
      assertEquals(1, journal.delegate.loadAll().records().size());

      fixture.store.transaction(
          tx -> {
            tx.lockTree(fixture.baseline.threadId());
            tx.lockThread(fixture.baseline.threadId());
            tx.deleteThreads(List.of(fixture.baseline.threadId()));
            return null;
          });
      fixture.clock.advance(Duration.ofSeconds(30));
      confirmer.replayPending();
      assertEquals(3, transactions.get(), "deep deletion must be observed before journal cleanup");
      assertTrue(journal.delegate.loadAll().records().isEmpty());
      assertTrue(
          fixture.store.transaction(tx -> tx.findExecution(missing.executionId())).isEmpty());
      assertEquals(1, logs.matching("reason=FACT_MISSING").size());
    }
  }

  @Test
  void invariantFailureAlertsOnceAndDoesNotBlockOtherExecutions() {
    Fixture fixture = Fixture.create();
    ExecutionExitFact missing = expected(fixture.baseline.threadId(), TestIds.id(20), T0);
    ExecutionExitFact present =
        register(fixture.store, fixture.baseline.threadId(), TestIds.id(21));
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    journal.write(recordFor(missing));
    journal.write(recordFor(present));
    AtomicInteger databaseTransactions = new AtomicInteger();
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(
            countingStore(fixture.store, databaseTransactions, new AtomicInteger()),
            journal,
            fixture.clock);

    try (LogCapture logs = LogCapture.attach()) {
      confirmer.replayPending();

      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          execution(fixture.store, present.executionId()).state());
      assertEquals(1, journal.delegate.loadAll().records().size());
      assertEquals(
          missing.executionId(), journal.delegate.loadAll().records().getFirst().executionId());
      assertEquals(1, logs.matching("executionId=" + missing.executionId()).size());

      databaseTransactions.set(0);
      fixture.clock.advance(Duration.ofSeconds(29));
      confirmer.replayPending();

      assertEquals(0, databaseTransactions.get(), "invariant failure waits for its slow recheck");
      fixture.clock.advance(Duration.ofSeconds(1));
      confirmer.replayPending();
      assertEquals(1, databaseTransactions.get(), "only the unresolved record needs rechecking");
      assertEquals(1, logs.matching("executionId=" + missing.executionId()).size());
      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          execution(fixture.store, present.executionId()).state());
    }
  }

  @Test
  void explicitConfirmAfterPermanentFailureAllowsTransientRetry() {
    Fixture fixture = Fixture.create();
    ExecutionExitFact missing = expected(fixture.baseline.threadId(), TestIds.id(22), T0);
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    journal.write(recordFor(missing));
    AtomicInteger databaseTransactions = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(
            countingStore(fixture.store, databaseTransactions, failures), journal, fixture.clock);

    confirmer.replayPending();
    assertEquals(1, databaseTransactions.get());
    assertEquals(1, journal.delegate.loadAll().records().size());

    register(fixture.store, missing);
    failures.set(1);
    fixture.clock.advance(Duration.ofSeconds(1));
    assertThrows(
        IllegalStateException.class,
        () -> confirmer.confirm(missing, ExecutionExitState.STUDIO_EXITED));
    assertEquals(2, databaseTransactions.get());
    assertEquals(1, journal.delegate.loadAll().records().size());

    fixture.clock.set(T0.plusMillis(1_999));
    confirmer.replayPending();
    assertEquals(2, databaseTransactions.get(), "the explicit transient failure keeps its backoff");

    fixture.clock.set(T0.plusSeconds(2));
    confirmer.replayPending();

    ExecutionExitFact confirmed = execution(fixture.store, missing.executionId());
    assertEquals(3, databaseTransactions.get());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(T0, confirmed.confirmedAt());
    assertTrue(journal.delegate.loadAll().records().isEmpty());
  }

  @Test
  void transientFailureWaitsForItsClockBackoffAndResetsAfterSuccess() {
    Fixture fixture = Fixture.create();
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    AtomicInteger databaseTransactions = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger(3);
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(
            countingStore(fixture.store, databaseTransactions, failures), journal, fixture.clock);

    assertThrows(
        IllegalStateException.class,
        () -> confirmer.confirm(fixture.fact, ExecutionExitState.STUDIO_EXITED));
    assertEquals(1, databaseTransactions.get());
    assertEquals(1, journal.delegate.loadAll().records().size());

    fixture.clock.set(T0.plusMillis(999));
    confirmer.replayPending();
    assertEquals(1, databaseTransactions.get(), "not due before the 1 second backoff");

    fixture.clock.set(T0.plusSeconds(1));
    confirmer.replayPending();
    assertEquals(2, databaseTransactions.get(), "due after the first backoff");
    assertEquals(1, journal.delegate.loadAll().records().size());

    fixture.clock.set(T0.plusMillis(2_999));
    confirmer.replayPending();
    assertEquals(2, databaseTransactions.get(), "not due before the second backoff");

    fixture.clock.set(T0.plusSeconds(3));
    confirmer.replayPending();
    assertEquals(3, databaseTransactions.get(), "due after the second backoff");

    fixture.clock.set(T0.plusSeconds(7));
    confirmer.replayPending();
    assertEquals(4, databaseTransactions.get(), "the final attempt succeeds");
    assertTrue(journal.delegate.loadAll().records().isEmpty());
    ExecutionExitFact confirmed = execution(fixture.store, fixture.fact.executionId());
    assertEquals(ExecutionExitState.STUDIO_EXITED, confirmed.state());
    assertEquals(T0, confirmed.confirmedAt());
  }

  @Test
  void directoryLoadFailureIsDedupedAndDoesNotBlockTheInMemoryProof() {
    Fixture fixture = Fixture.create();
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    journal.failWrites = 1;
    journal.failLoads = true;
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(fixture.store, journal, fixture.clock);

    assertThrows(
        ExecutionExitJournalException.class,
        () -> confirmer.confirm(fixture.fact, ExecutionExitState.STUDIO_EXITED));

    try (LogCapture logs = LogCapture.attach()) {
      fixture.clock.advance(Duration.ofSeconds(1));
      confirmer.replayPending();

      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          execution(fixture.store, fixture.fact.executionId()).state());
      assertEquals(1, journal.loads());
      assertEquals(1, logs.matching("phase=EXIT_CONFIRMATION_JOURNAL_LOAD").size());

      confirmer.replayPending();
      assertEquals(1, journal.loads(), "directory load must wait for its backoff");

      fixture.clock.advance(Duration.ofMillis(999));
      confirmer.replayPending();
      assertEquals(1, journal.loads(), "directory load is not due before one second");

      fixture.clock.advance(Duration.ofMillis(1));
      confirmer.replayPending();
      assertEquals(2, journal.loads(), "directory load is retried when due");
      assertEquals(1, logs.matching("phase=EXIT_CONFIRMATION_JOURNAL_LOAD").size());

      journal.failLoads = false;
      fixture.clock.advance(Duration.ofSeconds(2));
      confirmer.replayPending();
      assertEquals(3, journal.loads(), "a recovered directory load clears the failure state");

      confirmer.replayPending();
      assertEquals(4, journal.loads(), "healthy directory is loaded on every replay round");
    }
  }

  @Test
  void deleteFailureKeepsCommittedDatabaseAndUsesDedupedBackoff() {
    Fixture fixture = Fixture.create();
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    journal.failDeletes = 2;
    AtomicInteger databaseTransactions = new AtomicInteger();
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(
            countingStore(fixture.store, databaseTransactions, new AtomicInteger()),
            journal,
            fixture.clock);

    try (LogCapture logs = LogCapture.attach()) {
      assertEquals(
          ExecutionExitConfirmer.Outcome.CONFIRMED,
          confirmer.confirm(fixture.fact, ExecutionExitState.STUDIO_EXITED).outcome());
      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          execution(fixture.store, fixture.fact.executionId()).state());
      assertEquals(1, journal.delegate.loadAll().records().size());
      assertEquals(1, logs.matching("reason=JOURNAL_DELETE").size());

      databaseTransactions.set(0);
      confirmer.replayPending();
      assertEquals(0, databaseTransactions.get(), "delete retry must observe its backoff");
      assertEquals(1, journal.delegate.loadAll().records().size());

      fixture.clock.advance(Duration.ofSeconds(1));
      confirmer.replayPending();
      assertEquals(1, databaseTransactions.get());
      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          execution(fixture.store, fixture.fact.executionId()).state());
      assertEquals(1, logs.matching("reason=JOURNAL_DELETE").size());

      fixture.clock.advance(Duration.ofSeconds(2));
      confirmer.replayPending();
      assertEquals(2, databaseTransactions.get());
      assertTrue(journal.delegate.loadAll().records().isEmpty());
      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          execution(fixture.store, fixture.fact.executionId()).state());
    }
  }

  @Test
  void sameRoundDoesNotAttemptBothMemoryAndDiskProof() {
    Fixture fixture = Fixture.create();
    FaultJournal journal = new FaultJournal(TestExecutionExitConfirmer.memoryJournal());
    journal.failAfterDelegateWrites = 2;
    ExecutionExitConfirmer confirmer =
        new ExecutionExitConfirmer(fixture.store, journal, fixture.clock);

    assertThrows(
        ExecutionExitJournalException.class,
        () -> confirmer.confirm(fixture.fact, ExecutionExitState.STUDIO_EXITED));
    assertEquals(1, journal.writes());
    assertEquals(1, journal.delegate.loadAll().records().size());

    fixture.clock.advance(Duration.ofSeconds(1));
    confirmer.replayPending();

    assertEquals(
        2, journal.writes(), "the disk snapshot must not retry the same execution in one round");
    assertEquals(
        ExecutionExitState.UNCONFIRMED,
        execution(fixture.store, fixture.fact.executionId()).state());

    journal.failAfterDelegateWrites = 0;
    fixture.clock.advance(Duration.ofSeconds(2));
    confirmer.replayPending();

    assertEquals(
        ExecutionExitState.STUDIO_EXITED,
        execution(fixture.store, fixture.fact.executionId()).state());
    assertTrue(journal.delegate.loadAll().records().isEmpty());
  }

  @Test
  void badFileIsIsolatedAndLoggedWithoutItsNameOrMessage(@TempDir Path directory)
      throws IOException {
    Fixture fixture = Fixture.create();
    Path badFile = directory.resolve("secret-bad-entry.json");
    Files.writeString(badFile, "{}");
    try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory);
        LogCapture logs = LogCapture.attach()) {
      journal.write(recordFor(fixture.fact));
      ExecutionExitConfirmer confirmer =
          new ExecutionExitConfirmer(fixture.store, journal, fixture.clock);

      confirmer.replayPending();

      assertEquals(
          ExecutionExitState.STUDIO_EXITED,
          execution(fixture.store, fixture.fact.executionId()).state());
      ExecutionExitJournalSnapshot snapshot = journal.loadAll();
      assertTrue(snapshot.records().isEmpty());
      assertEquals(1, snapshot.failures().size());
      assertTrue(Files.exists(badFile));

      List<String> readLogs = logs.matching("phase=EXIT_CONFIRMATION_JOURNAL_READ");
      assertEquals(1, readLogs.size());
      assertTrue(readLogs.getFirst().contains("reason=CORRUPT"));
      assertFalse(readLogs.getFirst().contains("secret-bad-entry"));
      assertFalse(readLogs.getFirst().contains("{}"));
      assertFalse(readLogs.getFirst().contains("must be a JSON object"));

      confirmer.replayPending();
      assertEquals(
          1,
          logs.matching("phase=EXIT_CONFIRMATION_JOURNAL_READ").size(),
          "bad file alert is deduped");
    }
  }

  private static HarnessStore countingStore(
      HarnessStore delegate, AtomicInteger transactions, AtomicInteger failures) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("transaction")) {
                transactions.incrementAndGet();
                if (failures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                  throw new IllegalStateException("database unavailable");
                }
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException error) {
                throw error.getCause();
              }
            });
  }

  private static ExecutionExitFact register(
      InMemoryBuiltinHarnessStore store, UUID threadId, UUID invocationId) {
    ExecutionExitFact fact = expected(threadId, invocationId, T0);
    register(store, fact);
    return fact;
  }

  private static void register(InMemoryBuiltinHarnessStore store, ExecutionExitFact fact) {
    store.transaction(
        tx -> {
          tx.lockThread(fact.threadId());
          return tx.registerExecution(fact);
        });
  }

  private static ExecutionExitFact execution(HarnessStore store, UUID executionId) {
    return store.transaction(tx -> tx.findExecution(executionId).orElseThrow());
  }

  private static ExecutionExitFact expected(
      UUID threadId, UUID invocationId, Instant registeredAt) {
    return new ExecutionExitFact(
        UUID.randomUUID(),
        threadId,
        invocationId,
        1,
        "lease-1",
        ExecutionExitKind.TOOL,
        null,
        ExecutionExitState.UNCONFIRMED,
        registeredAt,
        null,
        null);
  }

  private static ExecutionExitJournalRecord recordFor(ExecutionExitFact fact) {
    return new ExecutionExitJournalRecord(
        fact.executionId(),
        fact.threadId(),
        fact.invocationId(),
        fact.attempt(),
        fact.ownerLeaseToken(),
        fact.kind(),
        fact.parentExecutionId(),
        ExecutionExitState.STUDIO_EXITED,
        T0);
  }

  private static final class Fixture {

    private final InMemoryBuiltinHarnessStore store;
    private final HarnessRuntimeTestSupport.Baseline baseline;
    private final ExecutionExitFact fact;
    private final MutableClock clock;

    private Fixture(
        InMemoryBuiltinHarnessStore store,
        HarnessRuntimeTestSupport.Baseline baseline,
        ExecutionExitFact fact,
        MutableClock clock) {
      this.store = store;
      this.baseline = baseline;
      this.fact = fact;
      this.clock = clock;
    }

    static Fixture create() {
      InMemoryBuiltinHarnessStore store = new InMemoryBuiltinHarnessStore();
      HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
      ExecutionExitFact fact = register(store, baseline.threadId(), TestIds.id(1));
      return new Fixture(store, baseline, fact, new MutableClock(T0));
    }
  }

  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    synchronized void set(Instant instant) {
      now = instant;
    }

    synchronized void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public synchronized Instant instant() {
      return now;
    }
  }

  private static final class FaultJournal implements ExecutionExitJournal {

    private final ExecutionExitJournal delegate;
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger loads = new AtomicInteger();
    private volatile int failWrites;
    private volatile int failAfterDelegateWrites;
    private volatile int failDeletes;
    private volatile boolean failLoads;

    private FaultJournal(ExecutionExitJournal delegate) {
      this.delegate = delegate;
    }

    @Override
    public synchronized ExecutionExitJournalRecord write(ExecutionExitJournalRecord record) {
      writes.incrementAndGet();
      if (failWrites > 0) {
        failWrites--;
        throw new ExecutionExitJournalException("cannot write");
      }
      ExecutionExitJournalRecord canonical = delegate.write(record);
      if (failAfterDelegateWrites > 0) {
        failAfterDelegateWrites--;
        throw new ExecutionExitJournalException("cannot force journal directory");
      }
      return canonical;
    }

    @Override
    public synchronized void delete(UUID executionId) {
      if (failDeletes > 0) {
        failDeletes--;
        throw new ExecutionExitJournalException("cannot delete");
      }
      delegate.delete(executionId);
    }

    @Override
    public ExecutionExitJournalSnapshot loadAll() {
      loads.incrementAndGet();
      if (failLoads) {
        throw new ExecutionExitJournalException("cannot list journal directory");
      }
      return delegate.loadAll();
    }

    @Override
    public void close() {
      delegate.close();
    }

    private int writes() {
      return writes.get();
    }

    private int loads() {
      return loads.get();
    }
  }

  private static final class LogCapture extends AppenderBase<ILoggingEvent>
      implements AutoCloseable {

    private final Logger logger;
    private final Level previousLevel;
    private final boolean previousAdditivity;
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    private LogCapture(Logger logger, Level previousLevel, boolean previousAdditivity) {
      this.logger = logger;
      this.previousLevel = previousLevel;
      this.previousAdditivity = previousAdditivity;
    }

    static LogCapture attach() {
      Logger logger = (Logger) LoggerFactory.getLogger(ExecutionExitConfirmer.class);
      LogCapture capture = new LogCapture(logger, logger.getLevel(), logger.isAdditive());
      capture.setContext(logger.getLoggerContext());
      capture.start();
      logger.setLevel(Level.WARN);
      logger.setAdditive(false);
      logger.addAppender(capture);
      return capture;
    }

    List<String> matching(String marker) {
      return events.stream()
          .map(ILoggingEvent::getFormattedMessage)
          .filter(message -> message.contains(marker))
          .toList();
    }

    @Override
    protected void append(ILoggingEvent event) {
      events.add(event);
    }

    @Override
    public void close() {
      logger.detachAppender(this);
      stop();
      logger.setLevel(previousLevel);
      logger.setAdditive(previousAdditivity);
    }
  }
}
