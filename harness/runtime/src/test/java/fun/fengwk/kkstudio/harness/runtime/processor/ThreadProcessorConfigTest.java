package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** ThreadProcessorConfig 的动态 resolve 延迟 provider 与必需依赖校验。 */
class ThreadProcessorConfigTest {

  private static final CompactionConfig COMPACTION = new CompactionConfig(20_000);

  @Test
  void resolveFailureDelayProviderUsesTheCurrentValue() {
    AtomicReference<Duration> current = new AtomicReference<>(Duration.ofMillis(10));
    ThreadProcessorConfig config = new ThreadProcessorConfig(current::get, () -> COMPACTION);
    assertEquals(Duration.ofMillis(10), config.resolveFailureDelayProvider().get());

    current.set(Duration.ofMillis(25));

    assertEquals(Duration.ofMillis(25), config.resolveFailureDelayProvider().get());
  }

  @Test
  void rejectsNullProviders() {
    assertThrows(
        NullPointerException.class, () -> new ThreadProcessorConfig(null, () -> COMPACTION));
    assertThrows(
        NullPointerException.class,
        () -> new ThreadProcessorConfig(() -> Duration.ofSeconds(1), null));
  }
}
