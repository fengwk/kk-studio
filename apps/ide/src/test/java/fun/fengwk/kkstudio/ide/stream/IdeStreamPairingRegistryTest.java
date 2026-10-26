package fun.fengwk.kkstudio.ide.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 意图：反向数据面配对必须一次性交付 peer；等待超时或在交付前被关闭时执行 abandon（放弃在途 OPEN_STREAM）并 fail-closed；代际结束 必须关闭该代际全部
 * pending 与 active 配对（含已建立的 peer），而不是只撤销票据；其它代际不受影响。
 */
class IdeStreamPairingRegistryTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final DaemonConnectionIdentity GENERATION =
      new DaemonConnectionIdentity(ENV, "daemon-1");

  private static final class RecordingPeer implements AutoCloseable {

    private final List<String> events;

    private RecordingPeer(List<String> events) {
      this.events = events;
    }

    @Override
    public void close() {
      events.add("peer-closed");
    }
  }

  @Test
  void pairDeliversPeerToAwaiter() {
    IdeStreamPairingRegistry<RecordingPeer> registry = new IdeStreamPairingRegistry<>();
    List<String> events = new CopyOnWriteArrayList<>();
    IdeStreamPairingRegistry<RecordingPeer>.Pairing pairing =
        registry.open("ticket", GENERATION, Duration.ofSeconds(5), () -> events.add("abandon"));
    RecordingPeer peer = new RecordingPeer(events);

    assertTrue(registry.pair("ticket", peer));
    assertSame(peer, pairing.awaitPeer());
    assertEquals(0, registry.pendingCount());
    assertEquals(1, registry.activeCount());
    pairing.close();
    assertEquals(List.of("peer-closed"), events);
  }

  @Test
  void unknownTicketIsRejectedAndLatePeerIsNotDelivered() {
    IdeStreamPairingRegistry<RecordingPeer> registry = new IdeStreamPairingRegistry<>();
    List<String> events = new CopyOnWriteArrayList<>();
    registry.open("ticket", GENERATION, Duration.ofMillis(50), () -> events.add("abandon"));

    assertFalse(registry.pair("other", new RecordingPeer(events)));
    // 等待超时：执行 abandon 并终结配对。
    IdeStreamPairingRegistry<RecordingPeer>.Pairing pairing =
        registry.open("late", GENERATION, Duration.ofMillis(50), () -> events.add("abandon-late"));
    assertThrows(IdeStreamPairingException.class, pairing::awaitPeer);
    assertEquals(List.of("abandon-late"), events);
    // 迟到的反向连接必须被拒绝（ticket 已终结），由适配层关闭该 peer。
    assertFalse(registry.pair("late", new RecordingPeer(events)));
  }

  @Test
  void closeGenerationClosesPendingAndActiveAndFiresAbandonOnce() {
    IdeStreamPairingRegistry<RecordingPeer> registry = new IdeStreamPairingRegistry<>();
    List<String> events = new CopyOnWriteArrayList<>();
    AtomicInteger abandons = new AtomicInteger();
    IdeStreamPairingRegistry<RecordingPeer>.Pairing pending =
        registry.open("pending", GENERATION, Duration.ofSeconds(5), abandons::incrementAndGet);
    IdeStreamPairingRegistry<RecordingPeer>.Pairing active =
        registry.open("active", GENERATION, Duration.ofSeconds(5), abandons::incrementAndGet);
    RecordingPeer activePeer = new RecordingPeer(events);
    assertTrue(registry.pair("active", activePeer));

    registry.closeGeneration(GENERATION);

    // 已交付的 peer 必须被关闭（不能只 revoke 票据）。
    assertEquals(List.of("peer-closed"), events);
    // 未交付的 pending 必须执行 abandon，且只执行一次。
    assertEquals(1, abandons.get());
    assertThrows(IdeStreamPairingException.class, pending::awaitPeer);
    assertEquals(0, registry.pendingCount());
    assertEquals(0, registry.activeCount());
  }

  @Test
  void closeGenerationReleasesBlockedAwaiter() throws InterruptedException {
    IdeStreamPairingRegistry<RecordingPeer> registry = new IdeStreamPairingRegistry<>();
    IdeStreamPairingRegistry<RecordingPeer>.Pairing pairing =
        registry.open("ticket", GENERATION, Duration.ofSeconds(30), () -> {});
    CountDownLatch started = new CountDownLatch(1);
    CopyOnWriteArrayList<Throwable> failures = new CopyOnWriteArrayList<>();
    Thread awaiter =
        new Thread(
            () -> {
              started.countDown();
              try {
                pairing.awaitPeer();
              } catch (Throwable error) {
                failures.add(error);
              }
            });
    awaiter.start();
    assertTrue(started.await(5, TimeUnit.SECONDS));
    // 给等待线程一点时间进入 wait。
    Thread.sleep(20);
    registry.closeGeneration(GENERATION);
    awaiter.join(5000);
    assertEquals(1, failures.size());
    assertTrue(failures.get(0) instanceof IdeStreamPairingException);
  }

  @Test
  void closeGenerationOnlyAffectsItsGeneration() {
    IdeStreamPairingRegistry<RecordingPeer> registry = new IdeStreamPairingRegistry<>();
    List<String> events = new CopyOnWriteArrayList<>();
    DaemonConnectionIdentity otherInstance = new DaemonConnectionIdentity(ENV, "daemon-2");
    IdeStreamPairingRegistry<RecordingPeer>.Pairing other =
        registry.open("other", otherInstance, Duration.ofSeconds(5), () -> events.add("abandon"));
    RecordingPeer peer = new RecordingPeer(events);
    assertTrue(registry.pair("other", peer));

    registry.closeGeneration(GENERATION);

    assertEquals(0, events.size());
    assertSame(peer, other.awaitPeer());
    assertEquals(1, registry.activeCount());
  }

  @Test
  void closeBeforePairFiresAbandonWithoutPeer() {
    IdeStreamPairingRegistry<RecordingPeer> registry = new IdeStreamPairingRegistry<>();
    AtomicInteger abandons = new AtomicInteger();
    IdeStreamPairingRegistry<RecordingPeer>.Pairing pairing =
        registry.open("ticket", GENERATION, Duration.ofSeconds(5), abandons::incrementAndGet);

    pairing.close();
    pairing.close();

    assertEquals(1, abandons.get());
    assertEquals(0, registry.pendingCount());
    assertFalse(registry.pair("ticket", new RecordingPeer(new CopyOnWriteArrayList<>())));
  }
}
