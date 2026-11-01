package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import static fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogFixtures.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogPageDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultCandidateDTO;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 6 小时缓存、single-flight、失败保留旧快照与游标分页的服务契约。 */
class ModelCatalogServiceTest {

  private static final String SERVICE_JSON = resource("service.json");
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  private final ModelsDevCatalogParser parser = new ModelsDevCatalogParser();
  private final MutableClock clock = new MutableClock(START);

  @Test
  void cachesSnapshotUntilSixHourTtl() {
    AtomicInteger calls = new AtomicInteger();
    ModelCatalogService service =
        service(
            () -> {
              calls.incrementAndGet();
              return SERVICE_JSON;
            });

    ModelCatalogPageDTO first = service.page(null, null, 20);
    assertEquals(List.of("d1", "d2", "d3", "g1", "g2"), modelIds(first));
    assertEquals(START, first.getFetchedAt());
    assertFalse(first.isLoading());
    assertNull(first.getError());
    assertEquals(1, calls.get());

    service.page(null, null, 20);
    assertEquals(1, calls.get(), "fresh snapshot must not refetch");

    clock.advance(Duration.ofHours(6));
    ModelCatalogPageDTO refreshed = service.page(null, null, 20);
    assertEquals(2, calls.get(), "six-hour boundary is expired");
    assertEquals(clock.instant(), refreshed.getFetchedAt());
  }

  @Test
  void coalescesConcurrentFirstLoads() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ModelCatalogService service =
        service(
            () -> {
              calls.incrementAndGet();
              entered.countDown();
              await(release);
              return SERVICE_JSON;
            });

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ModelCatalogPageDTO> first = executor.submit(() -> service.page(null, null, 20));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      Future<ModelCatalogPageDTO> second = executor.submit(() -> service.page(null, null, 20));
      Thread.sleep(150);
      assertEquals(1, calls.get(), "concurrent loads must share one fetch");
      release.countDown();

      assertEquals(5, first.get(5, TimeUnit.SECONDS).getItems().size());
      assertEquals(5, second.get(5, TimeUnit.SECONDS).getItems().size());
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void failedRefreshKeepsPreviousSnapshotAndRealFetchedAt() {
    AtomicInteger calls = new AtomicInteger();
    ModelCatalogService service =
        service(
            () -> {
              if (calls.incrementAndGet() == 2) {
                throw new ModelCatalogFetchException("upstream boom");
              }
              return SERVICE_JSON;
            });

    ModelCatalogPageDTO first = service.page(null, null, 20);
    Instant fetchedAt = first.getFetchedAt();
    List<String> ids = modelIds(first);

    clock.advance(Duration.ofHours(6).plusSeconds(1));
    ModelCatalogPageDTO failed = service.page(null, null, 20);

    assertEquals(ids, modelIds(failed));
    assertEquals(fetchedAt, failed.getFetchedAt());
    assertTrue(failed.getError().contains("upstream boom"));
    assertFalse(failed.isLoading());
    assertEquals(2, calls.get());
  }

  @Test
  void pagesWithStableCursorAndBoundedQuery() {
    AtomicInteger calls = new AtomicInteger();
    ModelCatalogService service =
        service(
            () -> {
              calls.incrementAndGet();
              return SERVICE_JSON;
            });

    ModelCatalogPageDTO first = service.page(null, null, 2);
    assertEquals(List.of("d1", "d2"), modelIds(first));
    assertNotNull(first.getNextCursor());

    ModelCatalogPageDTO second = service.page(null, first.getNextCursor(), 2);
    assertEquals(List.of("d3", "g1"), modelIds(second));
    assertNotNull(second.getNextCursor());

    ModelCatalogPageDTO third = service.page(null, second.getNextCursor(), 2);
    assertEquals(List.of("g2"), modelIds(third));
    assertNull(third.getNextCursor());
    assertEquals(1, calls.get(), "pagination must reuse the cached snapshot");

    // 空白/大小写查询归一化；无效、旧 generation 与超长 cursor 都回到第一页。
    assertEquals(List.of("d1", "d2", "d3"), modelIds(service.page("  DELTA  ", null, 50)));
    assertEquals(List.of("d1", "d2"), modelIds(service.page(null, "not-base64", 2)));
    assertEquals(List.of("d1", "d2"), modelIds(service.page(null, cursor("0:1"), 2)));
    assertEquals(List.of("d1", "d2"), modelIds(service.page(null, "x".repeat(513), 2)));
    assertEquals(List.of(), modelIds(service.page("x".repeat(5000), null, 20)));

    // pageSize 边界：非正数回落到默认值，超过上限截到 50。
    assertEquals(5, service.page(null, null, 0).getItems().size());
    assertEquals(5, service.page(null, null, -1).getItems().size());
    assertEquals(5, service.page(null, null, 100).getItems().size());
  }

  @Test
  void recordsFetchFailureWithoutCrashing() {
    AtomicInteger calls = new AtomicInteger();
    ModelCatalogService service =
        service(
            () -> {
              calls.incrementAndGet();
              throw new ModelCatalogFetchException("v2 unavailable");
            });

    ModelCatalogPageDTO page = service.page(null, null, 20);
    assertEquals(List.of(), modelIds(page));
    assertTrue(page.getError().contains("v2 unavailable"));
    assertEquals(1, calls.get());
  }

  private ModelCatalogService service(ModelCatalogTransport transport) {
    return new ModelCatalogService(transport, parser, clock);
  }

  private static List<String> modelIds(ModelCatalogPageDTO page) {
    return page.getItems().stream().map(ModelDefaultCandidateDTO::getModelId).toList();
  }

  private static String cursor(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for test latch");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for test latch", error);
    }
  }

  /** 可推进的 UTC clock，用于验证 6 小时 TTL 而不真实等待。 */
  private static final class MutableClock extends Clock {

    private final AtomicReference<Instant> current;

    private MutableClock(Instant start) {
      this.current = new AtomicReference<>(start);
    }

    void advance(Duration duration) {
      current.updateAndGet(instant -> instant.plus(duration));
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
    public Instant instant() {
      return current.get();
    }
  }
}
