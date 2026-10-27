package fun.fengwk.kkstudio.harness.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 定向回归：确认 stripe 与文件 journal 全局锁在单 carrier 虚拟线程上阻塞时不能产生 {@code jdk.VirtualThreadPinned}。
 *
 * <p>子进程固定 virtual-thread scheduler 为单 carrier，并在录制前完成类加载与场景预热；测试通过真实确认入口与 journal 写入制造阻塞，不增加生产
 * fault seam。
 */
class ExecutionExitVirtualThreadPinningTest {

  @Test
  void confirmationLocksDoNotPinVirtualThreadsDuringContention(@TempDir Path directory)
      throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process process =
        new ProcessBuilder(
                java,
                "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                "-cp",
                classpath,
                PinningProbe.class.getName(),
                directory.toString())
            .redirectErrorStream(true)
            .start();
    boolean exited = process.waitFor(30, TimeUnit.SECONDS);
    if (!exited) {
      process.destroyForcibly();
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(exited, () -> "pinning probe did not finish: " + output);
    assertEquals(0, process.exitValue(), output);
    assertTrue(output.contains("NO_PIN"), output);
  }

  /** test-only 子进程入口。 */
  public static final class PinningProbe {

    private PinningProbe() {}

    public static void main(String[] args) throws Exception {
      Path directory = Path.of(args[0]);
      HarnessStore store = blockingStore(new CountDownLatch(0), new CountDownLatch(0));
      ExecutionExitConfirmer confirmer =
          new ExecutionExitConfirmer(
              store, TestExecutionExitConfirmer.memoryJournal(), Clock.systemUTC());
      ExecutionExitFact warmupFact = fact();
      runStripeScenario(confirmer, warmupFact, new CountDownLatch(0), new CountDownLatch(0));
      warmupFileLock(directory.resolve("warmup"));

      Recording recording = new Recording();
      recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
      recording.start();

      CountDownLatch databaseEntered = new CountDownLatch(1);
      CountDownLatch releaseDatabase = new CountDownLatch(1);
      ExecutionExitConfirmer blockedConfirmer =
          new ExecutionExitConfirmer(
              blockingStore(databaseEntered, releaseDatabase),
              TestExecutionExitConfirmer.memoryJournal(),
              Clock.systemUTC());
      runStripeScenario(blockedConfirmer, fact(), databaseEntered, releaseDatabase);

      Path journalDirectory = directory.resolve("journal");
      try (FileExecutionExitJournal journal = new FileExecutionExitJournal(journalDirectory)) {
        runFileLockScenario(journal);
      }

      Path dump = Files.createTempFile(directory, "pinning", ".jfr");
      recording.stop();
      recording.dump(dump);
      long pinned = 0;
      for (RecordedEvent event : RecordingFile.readAllEvents(dump)) {
        if (event.getEventType().getName().equals("jdk.VirtualThreadPinned")) {
          pinned++;
        }
      }
      recording.close();
      Files.deleteIfExists(dump);
      System.out.println(pinned == 0 ? "NO_PIN" : "PINNED=" + pinned);
      System.out.flush();
    }

    private static void runStripeScenario(
        ExecutionExitConfirmer confirmer,
        ExecutionExitFact fact,
        CountDownLatch databaseEntered,
        CountDownLatch releaseDatabase)
        throws Exception {
      Thread holder =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      confirmer.confirm(fact, ExecutionExitState.STUDIO_EXITED);
                    } catch (RuntimeException ignored) {
                      // 数据库由测试代理拒绝；这里只验证 stripe 阻塞期间是否 pin。
                    }
                  });
      if (databaseEntered.getCount() > 0) {
        assertTrue(databaseEntered.await(10, TimeUnit.SECONDS), "holder did not enter database");
      }
      CountDownLatch contenderStarted = new CountDownLatch(1);
      Thread contender =
          Thread.ofVirtual()
              .start(
                  () -> {
                    contenderStarted.countDown();
                    try {
                      confirmer.confirm(fact, ExecutionExitState.STUDIO_EXITED);
                    } catch (RuntimeException ignored) {
                      // 同一 execution 的第二个回调只需阻塞在 stripe 上。
                    }
                  });
      assertTrue(contenderStarted.await(10, TimeUnit.SECONDS));
      Thread.sleep(250);
      releaseDatabase.countDown();
      holder.join(10_000);
      contender.join(10_000);
      if (holder.isAlive() || contender.isAlive()) {
        throw new IllegalStateException("stripe scenario did not finish");
      }
    }

    private static void runFileLockScenario(FileExecutionExitJournal journal) throws Exception {
      ReentrantLock lock = fileJournalLock(journal);
      CountDownLatch acquired = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      Thread holder =
          Thread.ofVirtual()
              .start(
                  () -> {
                    lock.lock();
                    try {
                      acquired.countDown();
                      release.await();
                    } catch (InterruptedException error) {
                      Thread.currentThread().interrupt();
                    } finally {
                      lock.unlock();
                    }
                  });
      assertTrue(acquired.await(10, TimeUnit.SECONDS), "file journal lock was not acquired");
      ExecutionExitJournalRecord record =
          new ExecutionExitJournalRecord(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              1,
              "lease-1",
              ExecutionExitKind.TOOL,
              null,
              ExecutionExitState.STUDIO_EXITED,
              Instant.EPOCH);
      CountDownLatch contenderStarted = new CountDownLatch(1);
      Thread contender =
          Thread.ofVirtual()
              .start(
                  () -> {
                    contenderStarted.countDown();
                    journal.write(record);
                  });
      assertTrue(contenderStarted.await(10, TimeUnit.SECONDS));
      Thread.sleep(250);
      release.countDown();
      holder.join(10_000);
      contender.join(10_000);
      if (holder.isAlive() || contender.isAlive()) {
        throw new IllegalStateException("file journal scenario did not finish");
      }
    }

    private static void warmupFileLock(Path directory) throws Exception {
      try (FileExecutionExitJournal journal = new FileExecutionExitJournal(directory)) {
        ReentrantLock lock = fileJournalLock(journal);
        lock.lock();
        lock.unlock();
        journal.write(
            new ExecutionExitJournalRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "lease-1",
                ExecutionExitKind.TOOL,
                null,
                ExecutionExitState.STUDIO_EXITED,
                Instant.EPOCH));
      }
    }

    private static ReentrantLock fileJournalLock(FileExecutionExitJournal journal)
        throws Exception {
      Field field = FileExecutionExitJournal.class.getDeclaredField("writeLock");
      field.setAccessible(true);
      return (ReentrantLock) field.get(journal);
    }

    private static HarnessStore blockingStore(CountDownLatch entered, CountDownLatch release) {
      AtomicBoolean blockNext = new AtomicBoolean(entered.getCount() > 0);
      return (HarnessStore)
          Proxy.newProxyInstance(
              HarnessStore.class.getClassLoader(),
              new Class<?>[] {HarnessStore.class},
              (proxy, method, args) -> {
                if (method.getName().equals("transaction")
                    && blockNext.compareAndSet(true, false)) {
                  entered.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                  }
                }
                throw new IllegalStateException("test database is unavailable");
              });
    }

    private static ExecutionExitFact fact() {
      return new ExecutionExitFact(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          1,
          "lease-1",
          ExecutionExitKind.TOOL,
          null,
          ExecutionExitState.UNCONFIRMED,
          Instant.EPOCH,
          null,
          null);
    }
  }
}
