package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * MiniMax Mavis 能力目录缓存（MiniMaxMavisCapabilityCache）测试。
 *
 * <p>验证 5 分钟短 TTL 内复用目录（单次 HTTP 请求）、TTL 过期后重新请求网关、 以及目录获取失败时不写入缓存的 Fail-Closed 行为。
 */
class MiniMaxMavisCapabilityCacheTest {

  private static final Instant BASE = Instant.ofEpochSecond(1_700_000_000L);
  private static final String TOKEN = MavisTestTokens.withExp(1_700_000_000L + 3600L);
  private static final String CLIENT_UUID = "11111111-2222-3333-4444-555555555555";

  private MutableClock clock;
  private MavisClient mockClient;
  private MiniMaxMavisCapabilityCache cache;

  @BeforeEach
  void setUp() {
    this.clock = new MutableClock(BASE);
    this.mockClient = mock(MavisClient.class);
    this.cache = new MiniMaxMavisCapabilityCache(mockClient, clock);
  }

  /** 验证在 5 分钟 TTL 窗口内，多次请求复用已有目录，不重复发起 fetchCatalog。 */
  @Test
  void reusesCachedCatalogWithinTtl() {
    MavisCredential credential =
        new MavisCredential(TOKEN, MavisRegion.CN, BASE.plusSeconds(3600), CLIENT_UUID);
    MavisCapabilityCatalog catalog1 = new MavisCapabilityCatalog(Set.of("web_search"));

    when(mockClient.fetchCatalog(TOKEN, MavisRegion.CN)).thenReturn(catalog1);

    // 第一次调用：缓存未命中，触发 client 调用
    MavisCapabilityCatalog result1 = cache.catalog(credential);
    assertNotNull(result1);
    verify(mockClient, times(1)).fetchCatalog(TOKEN, MavisRegion.CN);

    // 前进 4 分钟 59 秒（仍在 5 分钟 TTL 内）
    clock.advance(Duration.ofMinutes(4).plusSeconds(59));

    // 第二次调用：命中缓存，直接返回相同实例
    MavisCapabilityCatalog result2 = cache.catalog(credential);
    assertSame(result1, result2, "Must return cached instance within TTL");
    verify(mockClient, times(1)).fetchCatalog(TOKEN, MavisRegion.CN);
  }

  /** 验证当时间超出 5 分钟 TTL 后，缓存失效并重新触发线上目录获取。 */
  @Test
  void refetchesCatalogAfterTtlExpires() {
    MavisCredential credential =
        new MavisCredential(TOKEN, MavisRegion.CN, BASE.plusSeconds(3600), CLIENT_UUID);
    MavisCapabilityCatalog catalog1 = new MavisCapabilityCatalog(Set.of("web_search"));
    MavisCapabilityCatalog catalog2 =
        new MavisCapabilityCatalog(Set.of("web_search", "synthesize_speech"));

    when(mockClient.fetchCatalog(TOKEN, MavisRegion.CN)).thenReturn(catalog1).thenReturn(catalog2);

    // 第一次获取
    MavisCapabilityCatalog result1 = cache.catalog(credential);
    assertEquals(catalog1, result1);

    // 前进 5 分钟 1 秒（已过期）
    clock.advance(Duration.ofMinutes(5).plusSeconds(1));

    // 第二次获取：必须重新从 client 请求
    MavisCapabilityCatalog result2 = cache.catalog(credential);
    assertEquals(catalog2, result2);
    verify(mockClient, times(2)).fetchCatalog(TOKEN, MavisRegion.CN);
  }

  /** 验证目录请求失败时不写缓存，后续调用会重试请求而不是缓存异常或空结果。 */
  @Test
  void doesNotCacheFailedCatalogRequests() {
    MavisCredential credential =
        new MavisCredential(TOKEN, MavisRegion.CN, BASE.plusSeconds(3600), CLIENT_UUID);

    when(mockClient.fetchCatalog(TOKEN, MavisRegion.CN))
        .thenThrow(new MavisTransportException("network unavailable"))
        .thenReturn(new MavisCapabilityCatalog(Set.of("web_search")));

    // 第一次调用失败
    assertThrows(MavisTransportException.class, () -> cache.catalog(credential));

    // 紧接着的第二次调用：由于未缓存失败，应立即再次调用 client
    MavisCapabilityCatalog result = cache.catalog(credential);
    assertNotNull(result);
    assertEquals(Set.of("web_search"), result.endpoints());
    verify(mockClient, times(2)).fetchCatalog(TOKEN, MavisRegion.CN);
  }

  /** 验证不同 region 按分桶独立缓存，相互之间不覆盖不串扰。 */
  @Test
  void partitionsCacheByRegion() {
    MavisCredential credentialCn =
        new MavisCredential(TOKEN, MavisRegion.CN, BASE.plusSeconds(3600), CLIENT_UUID);
    MavisCredential credentialEn =
        new MavisCredential(TOKEN, MavisRegion.EN, BASE.plusSeconds(3600), CLIENT_UUID);

    MavisCapabilityCatalog catalogCn = new MavisCapabilityCatalog(Set.of("web_search"));
    MavisCapabilityCatalog catalogEn = new MavisCapabilityCatalog(Set.of("synthesize_speech"));

    when(mockClient.fetchCatalog(TOKEN, MavisRegion.CN)).thenReturn(catalogCn);
    when(mockClient.fetchCatalog(TOKEN, MavisRegion.EN)).thenReturn(catalogEn);

    MavisCapabilityCatalog resultCn = cache.catalog(credentialCn);
    MavisCapabilityCatalog resultEn = cache.catalog(credentialEn);

    assertEquals(catalogCn, resultCn);
    assertEquals(catalogEn, resultEn);
    verify(mockClient, times(1)).fetchCatalog(TOKEN, MavisRegion.CN);
    verify(mockClient, times(1)).fetchCatalog(TOKEN, MavisRegion.EN);
  }

  /** 可调整当前时刻的测试时钟。 */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant initial) {
      this.now = initial;
    }

    void advance(Duration duration) {
      this.now = this.now.plus(duration);
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
      return now;
    }
  }
}
