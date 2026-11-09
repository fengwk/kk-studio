package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionIdentity;
import fun.fengwk.kkstudio.harness.spi.execution.LocalExecutionRecovery;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 独立子进程写入 PREPARED 后被 SIGKILL，新实例使用同一目录恢复旧 owner 的本地执行。 */
class SigkillRecoveryTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);

  @TempDir Path directory;

  @Test
  void sigkillReleasesDirectoryLockAndRecoversOwnerExited() throws Exception {
    Assumptions.assumeFalse(
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"),
        "SIGKILL semantics are POSIX-only");

    InMemoryHarnessStore store = new InMemoryHarnessStore();
    store.setClock(CLOCK);
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    UUID executionId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID ownerLeaseToken = UUID.randomUUID();
    LocalExecutionIdentity identity =
        new LocalExecutionIdentity(
            executionId,
            baseline.threadId(),
            invocationId,
            1,
            ownerLeaseToken,
            ExecutionExitKind.TOOL);
    register(store, identity);

    Process child = startPreparedChild(identity);
    try (BufferedReader output =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      String marker = output.readLine();
      assertEquals("PREPARED", marker, "child must persist PREPARED before the kill");
      child.destroyForcibly();
      assertTrue(child.waitFor(10, TimeUnit.SECONDS), "SIGKILLed child must terminate");
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
      }
    }

    try (ExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
      ExecutionExitConfirmer confirmer = new ExecutionExitConfirmer(store, journal, CLOCK);
      LocalExecutionRecovery recovery = confirmer.recoverPreviousRun();

      assertEquals(1, recovery.ownerExited().size(), "old PREPARED must become OWNER_EXITED");
      assertEquals(executionId, recovery.ownerExited().getFirst());
      ExecutionExitFact recovered =
          store.transaction(tx -> tx.exits().find(executionId)).orElseThrow();
      assertEquals(ExecutionExitState.OWNER_EXITED, recovered.state());
      ExecutionExitJournalSnapshot snapshot = journal.loadAll();
      assertTrue(snapshot.records().isEmpty(), "confirmed recovery must clean the journal record");
    }
  }

  private Process startPreparedChild(LocalExecutionIdentity identity) throws Exception {
    String java =
        Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path", ""));
    return new ProcessBuilder(
            java,
            "-cp",
            classpath,
            SigkillPreparedFixture.class.getName(),
            directory.toAbsolutePath().toString(),
            identity.executionId().toString(),
            identity.threadId().toString(),
            identity.invocationId().toString(),
            identity.ownerLeaseToken().toString())
        .redirectErrorStream(true)
        .start();
  }

  private static void register(InMemoryHarnessStore store, LocalExecutionIdentity identity) {
    store.transaction(
        tx -> {
          tx.threads().lock(identity.threadId());
          return tx.exits()
              .register(
                  new ExecutionExitFact(
                      identity.executionId(),
                      identity.threadId(),
                      identity.invocationId(),
                      identity.attempt(),
                      identity.ownerLeaseToken(),
                      identity.kind(),
                      null,
                      null,
                      ExecutionExitState.UNCONFIRMED,
                      CLOCK.instant(),
                      null,
                      null));
        });
  }
}
