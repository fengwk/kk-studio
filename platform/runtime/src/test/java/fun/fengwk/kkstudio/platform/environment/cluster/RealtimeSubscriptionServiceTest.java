package fun.fengwk.kkstudio.platform.environment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** RealtimeSubscriptionService：禁用边界、按 Thread 缓存、续租/注销串行与失败恢复 resync。 */
class RealtimeSubscriptionServiceTest {

  private static final UUID LOCAL = UUID.fromString("00000000-0000-0000-0000-000000000900");
  private static final UUID THREAD = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private JdbcClient jdbc;
  private JdbcClient.StatementSpec upsert;
  private JdbcClient.StatementSpec delete;
  private JdbcClient.StatementSpec deleteAll;
  private JdbcClient.StatementSpec query;
  private JdbcClient.MappedQuerySpec<Boolean> queryResult;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcClient.class);
    upsert = updateStatement(1);
    delete = updateStatement(1);
    deleteAll = updateStatement(1);
    query = mock(JdbcClient.StatementSpec.class);
    queryResult = mock(JdbcClient.MappedQuerySpec.class);
    when(jdbc.sql(RealtimeSubscriptionService.UPSERT_SQL)).thenReturn(upsert);
    when(jdbc.sql(RealtimeSubscriptionService.DELETE_SQL)).thenReturn(delete);
    when(jdbc.sql(RealtimeSubscriptionService.DELETE_ALL_SQL)).thenReturn(deleteAll);
    when(jdbc.sql(RealtimeSubscriptionService.HAS_REMOTE_SUBSCRIPTION_SQL)).thenReturn(query);
    when(query.param(anyString(), any())).thenReturn(query);
    when(query.query(Boolean.class)).thenReturn(queryResult);
  }

  /** 未启用集群时不登记、不续租、不查询、不发布任何跨节点订阅事实。 */
  @Test
  void disabledClusterSkipsEveryDatabaseOperation() {
    RealtimeSubscriptionService service =
        new RealtimeSubscriptionService(jdbc, LOCAL, new ClusterProperties());

    service.register(THREAD, () -> {});
    service.renewLocalSubscriptions();
    assertFalse(service.hasRemoteSubscription(THREAD));
    service.unregister(THREAD);
    service.releaseLocalSubscriptions();

    verifyNoInteractions(jdbc);
  }

  /** 缓存按 Thread 隔离，一秒窗口内复用；窗口过期后重新查询数据库现在时快照。 */
  @Test
  void remotePresenceCacheIsPerThreadAndExpires() {
    when(queryResult.single()).thenReturn(true, false, true);
    AtomicLong now = new AtomicLong(1_000_000_000L);
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), now::get);
    UUID otherThread = UUID.fromString("00000000-0000-0000-0000-000000000002");

    assertTrue(service.hasRemoteSubscription(THREAD));
    now.addAndGet(999_999_999L);
    assertTrue(service.hasRemoteSubscription(THREAD));
    assertFalse(service.hasRemoteSubscription(otherThread));
    verify(jdbc, times(2)).sql(RealtimeSubscriptionService.HAS_REMOTE_SUBSCRIPTION_SQL);

    now.addAndGet(1L);
    assertTrue(service.hasRemoteSubscription(THREAD));
    verify(jdbc, times(3)).sql(RealtimeSubscriptionService.HAS_REMOTE_SUBSCRIPTION_SQL);
  }

  /** 查询失败必须向上抛出，且失败结果不能进入缓存。 */
  @Test
  void queryFailurePropagatesAndIsNotCached() {
    IllegalStateException failure = new IllegalStateException("database unavailable");
    when(queryResult.single()).thenThrow(failure).thenReturn(true);
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), System::nanoTime);

    assertThrows(IllegalStateException.class, () -> service.hasRemoteSubscription(THREAD));
    assertTrue(service.hasRemoteSubscription(THREAD));
    verify(jdbc, times(2)).sql(RealtimeSubscriptionService.HAS_REMOTE_SUBSCRIPTION_SQL);
  }

  /** 缓存容量有界：超过上限时按访问顺序淘汰最旧条目，内存不随 Thread 数无限增长。 */
  @Test
  void remotePresenceCacheIsBounded() {
    when(queryResult.single()).thenReturn(false);
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), System::nanoTime);

    for (int i = 0; i <= RealtimeSubscriptionService.MAX_CACHE_ENTRIES; i++) {
      service.hasRemoteSubscription(new UUID(0L, i));
    }

    assertEquals(RealtimeSubscriptionService.MAX_CACHE_ENTRIES, service.cachedRemoteEntries());
  }

  /** 登记使用节点租约节奏的数据库时间，重复登记只累计本地引用计数。 */
  @Test
  void registerAndUnregisterUseTheNodeLeaseAndReferenceCount() {
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), System::nanoTime);

    service.register(THREAD, () -> {});
    service.register(THREAD, () -> {});
    verify(upsert, times(2)).param("threadId", THREAD);
    verify(upsert, times(2)).param("nodeInstanceId", LOCAL);
    verify(upsert, times(2)).param("leaseMillis", 45_000L);

    service.unregister(THREAD);
    verify(delete, never()).update();
    service.unregister(THREAD);
    verify(delete).param("threadId", THREAD);
    verify(delete).param("nodeInstanceId", LOCAL);
    verify(delete).update();
  }

  /** 续租失败与恢复各触发一次 resync，连续失败不重复刷屏。 */
  @Test
  void renewalResyncsOnFailureAndRecovery() {
    AtomicInteger resyncs = new AtomicInteger();
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), System::nanoTime);
    service.register(THREAD, resyncs::incrementAndGet);
    when(upsert.update()).thenThrow(new IllegalStateException("database unavailable"));

    service.renewLocalSubscriptions();
    assertEquals(1, resyncs.get());
    service.renewLocalSubscriptions();
    assertEquals(1, resyncs.get(), "continuous failure must not emit duplicate resync");

    doReturn(1).when(upsert).update();
    service.renewLocalSubscriptions();
    assertEquals(2, resyncs.get(), "recovery must cover the notification gap");
  }

  /** 续租触发的 resync 必须在服务锁外执行：回调内由另一线程完成注销，若锁仍被持有会等待超时。 */
  @Test
  void renewalResyncCallbackRunsOutsideServiceLock() throws Exception {
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), System::nanoTime);
    AtomicBoolean callbackInvoked = new AtomicBoolean();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      service.register(
          THREAD,
          () -> {
            callbackInvoked.set(true);
            Future<?> unregister = executor.submit(() -> service.unregister(THREAD));
            try {
              unregister.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              throw new AssertionError("interrupted while waiting for service call", error);
            } catch (ExecutionException | TimeoutException error) {
              throw new AssertionError(
                  "resync callback ran while the service lock was held", error);
            }
          });
      when(upsert.update()).thenThrow(new IllegalStateException("database unavailable"));

      service.renewLocalSubscriptions();

      assertTrue(callbackInvoked.get());
      verify(delete).update();
    } finally {
      executor.shutdownNow();
    }
  }

  /** 某个 Thread 的外键失败不能阻断其它 Thread 续租；每个 Thread 独立进入失败与恢复状态。 */
  @Test
  void renewalIsolatesThreadFailuresAndRecoversEachThreadIndependently() {
    UUID failingThread = THREAD;
    UUID healthyThread = UUID.fromString("00000000-0000-0000-0000-000000000002");
    AtomicInteger failingResyncs = new AtomicInteger();
    AtomicInteger healthyResyncs = new AtomicInteger();
    AtomicLong now = new AtomicLong();
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), now::get);
    service.register(failingThread, failingResyncs::incrementAndGet);
    service.register(healthyThread, healthyResyncs::incrementAndGet);
    clearInvocations(upsert);

    AtomicReference<UUID> currentThread = new AtomicReference<>();
    when(upsert.param(anyString(), any()))
        .thenAnswer(
            invocation -> {
              if ("threadId".equals(invocation.getArgument(0))) {
                currentThread.set(invocation.getArgument(1));
              }
              return upsert;
            });
    when(upsert.update())
        .thenAnswer(
            invocation -> {
              if (failingThread.equals(currentThread.get())) {
                throw new IllegalStateException("deleted thread");
              }
              return 1;
            });

    service.renewLocalSubscriptions();
    assertEquals(1, failingResyncs.get());
    assertEquals(0, healthyResyncs.get());
    verify(upsert, times(2)).update();

    service.renewLocalSubscriptions();
    assertEquals(1, failingResyncs.get(), "continuous failure must not emit duplicate resync");
    assertEquals(0, healthyResyncs.get());
    verify(upsert, times(4)).update();

    doReturn(1).when(upsert).update();
    service.renewLocalSubscriptions();
    assertEquals(2, failingResyncs.get(), "recovery must exclusively resync the failed Thread");
    assertEquals(0, healthyResyncs.get());
  }

  /** 心跳提前返回或长停顿后，即使 SQL 成功，只要相邻成功续租间隔达到租约也触发一次 resync。 */
  @Test
  void successfulRenewalAfterLeaseLengthGapTriggersRecoveryResync() {
    long leaseNanos = Duration.ofSeconds(45).toNanos();
    AtomicLong now = new AtomicLong();
    AtomicInteger resyncs = new AtomicInteger();
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), now::get);
    service.register(THREAD, resyncs::incrementAndGet);

    long halfLease = leaseNanos / 2;
    now.addAndGet(halfLease);
    service.renewLocalSubscriptions();
    assertEquals(0, resyncs.get());

    now.addAndGet(leaseNanos);
    service.renewLocalSubscriptions();
    assertEquals(1, resyncs.get(), "gap equal to the lease may hide notifications");

    service.renewLocalSubscriptions();
    assertEquals(1, resyncs.get(), "continuous healthy renewals must not resync repeatedly");
  }

  /** 登记失败保留本地跟踪，后续成功续租恢复并触发一次 resync。 */
  @Test
  void registrationFailureStaysTrackedForHeartbeatRecovery() {
    AtomicInteger resyncs = new AtomicInteger();
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), System::nanoTime);
    when(upsert.update())
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(1);

    assertThrows(
        IllegalStateException.class, () -> service.register(THREAD, resyncs::incrementAndGet));
    service.renewLocalSubscriptions();

    assertEquals(1, resyncs.get());
    verify(upsert, times(2)).update();
  }

  /** 单个 Thread 的 resync 回调异常不能阻止同一轮其它 Thread 的恢复通知。 */
  @Test
  void resyncCallbackFailureDoesNotBlockRemainingThreads() {
    UUID otherThread = UUID.fromString("00000000-0000-0000-0000-000000000002");
    AtomicInteger successfulResyncs = new AtomicInteger();
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), System::nanoTime);
    service.register(
        THREAD,
        () -> {
          throw new IllegalStateException("sink unavailable");
        });
    service.register(otherThread, successfulResyncs::incrementAndGet);
    when(upsert.update()).thenThrow(new IllegalStateException("database unavailable"));

    service.renewLocalSubscriptions();

    assertEquals(1, successfulResyncs.get());
  }

  /** 注销与续租共用服务锁：迟到的续租在最后释放后不得复活租约行。 */
  @Test
  void lateRenewalCannotResurrectAfterUnregister() throws Exception {
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), System::nanoTime);
    service.register(THREAD, () -> {});
    CountDownLatch renewalEntered = new CountDownLatch(1);
    CountDownLatch releaseRenewal = new CountDownLatch(1);
    when(upsert.update())
        .thenAnswer(
            invocation -> {
              renewalEntered.countDown();
              assertTrue(releaseRenewal.await(5, TimeUnit.SECONDS));
              return 1;
            });
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> renewal = executor.submit(service::renewLocalSubscriptions);
      assertTrue(renewalEntered.await(5, TimeUnit.SECONDS));
      Future<?> unregister = executor.submit(() -> service.unregister(THREAD));
      assertThrows(
          TimeoutException.class,
          () -> unregister.get(100, TimeUnit.MILLISECONDS),
          "unregister must wait for the in-flight renewal");
      releaseRenewal.countDown();
      renewal.get(5, TimeUnit.SECONDS);
      unregister.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    verify(delete).update();
    reset(upsert);
    when(upsert.param(anyString(), any())).thenReturn(upsert);
    when(upsert.update()).thenReturn(1);
    service.renewLocalSubscriptions();
    verify(upsert, never()).update();
  }

  /** 正常退出删除本节点全部租约并封闭生命周期边界：迟到 register/renew 不得复活。 */
  @Test
  void releaseDeletesAllLocalSubscriptionsAndPreventsLateRegistration() {
    RealtimeSubscriptionService service = service(Duration.ofSeconds(1), System::nanoTime);
    service.register(THREAD, () -> {});
    clearInvocations(upsert);

    service.releaseLocalSubscriptions();
    service.releaseLocalSubscriptions();
    verify(deleteAll, times(1)).param("nodeInstanceId", LOCAL);
    verify(deleteAll, times(1)).update();

    service.register(THREAD, () -> {});
    service.unregister(THREAD);
    service.renewLocalSubscriptions();
    verify(upsert, never()).update();
    verify(delete, never()).update();
  }

  private RealtimeSubscriptionService service(Duration ttl, LongSupplier clock) {
    return new RealtimeSubscriptionService(jdbc, LOCAL, properties(), ttl, clock);
  }

  private static JdbcClient.StatementSpec updateStatement(int rows) {
    JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
    when(statement.param(anyString(), any())).thenReturn(statement);
    when(statement.update()).thenReturn(rows);
    return statement;
  }

  private static ClusterProperties properties() {
    ClusterProperties properties = new ClusterProperties();
    properties.setInternalUrl("http://node-a.internal:8080");
    properties.setSecret("test-cluster-secret-0123456789abcdef");
    properties.setNodeLease(Duration.ofSeconds(45));
    properties.setHeartbeatInterval(Duration.ofSeconds(15));
    return properties;
  }
}
