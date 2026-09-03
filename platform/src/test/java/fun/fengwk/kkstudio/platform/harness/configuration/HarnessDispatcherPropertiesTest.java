package fun.fengwk.kkstudio.platform.harness.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class HarnessDispatcherPropertiesTest {

  /** 部署未覆盖配置时必须保持迁移前的容量与调度默认值不变。 */
  @Test
  void providesStableDeploymentDefaults() {
    HarnessDispatcherProperties properties = new HarnessDispatcherProperties();

    assertEquals(Duration.ofSeconds(30), properties.getLeaseDuration());
    assertEquals(Duration.ofSeconds(1), properties.getPollInterval());
    assertEquals(Duration.ofSeconds(1), properties.getRejectionDelay());
    assertEquals(64, properties.getMaxDispatchTasks());
    assertEquals(16, properties.getWorker().getConcurrency());
    assertEquals(64, properties.getWorker().getQueueCapacity());
  }

  /** JavaBean 绑定 setter 接受合法的正整毫秒时长与正整数容量覆盖。 */
  @Test
  void acceptsValidOverrides() {
    HarnessDispatcherProperties properties = new HarnessDispatcherProperties();
    HarnessDispatcherProperties.Worker worker = properties.getWorker();

    properties.setLeaseDuration(Duration.ofSeconds(45));
    properties.setPollInterval(Duration.ofMillis(250));
    properties.setRejectionDelay(Duration.ofMillis(500));
    properties.setMaxDispatchTasks(7);
    worker.setConcurrency(3);
    worker.setQueueCapacity(5);

    assertEquals(Duration.ofSeconds(45), properties.getLeaseDuration());
    assertEquals(Duration.ofMillis(250), properties.getPollInterval());
    assertEquals(Duration.ofMillis(500), properties.getRejectionDelay());
    assertEquals(7, properties.getMaxDispatchTasks());
    assertEquals(3, worker.getConcurrency());
    assertEquals(5, worker.getQueueCapacity());
  }

  /** Duration 与容量在输入 null、ZERO 或负数等非正值时 fail-fast。 */
  @Test
  void rejectsNonPositiveValues() {
    HarnessDispatcherProperties properties = new HarnessDispatcherProperties();
    HarnessDispatcherProperties.Worker worker = properties.getWorker();

    assertThrows(IllegalArgumentException.class, () -> properties.setLeaseDuration(null));
    assertThrows(IllegalArgumentException.class, () -> properties.setLeaseDuration(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setLeaseDuration(Duration.ofMillis(-1)));

    assertThrows(IllegalArgumentException.class, () -> properties.setPollInterval(null));
    assertThrows(IllegalArgumentException.class, () -> properties.setPollInterval(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setPollInterval(Duration.ofMillis(-1)));

    assertThrows(IllegalArgumentException.class, () -> properties.setRejectionDelay(null));
    assertThrows(IllegalArgumentException.class, () -> properties.setRejectionDelay(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setRejectionDelay(Duration.ofMillis(-1)));

    assertThrows(IllegalArgumentException.class, () -> properties.setMaxDispatchTasks(0));
    assertThrows(IllegalArgumentException.class, () -> properties.setMaxDispatchTasks(-1));

    assertThrows(IllegalArgumentException.class, () -> worker.setConcurrency(0));
    assertThrows(IllegalArgumentException.class, () -> worker.setConcurrency(-1));

    assertThrows(IllegalArgumentException.class, () -> worker.setQueueCapacity(0));
    assertThrows(IllegalArgumentException.class, () -> worker.setQueueCapacity(-1));
  }

  /** Dispatcher 的调度与租约时长必须与存储引擎毫秒精度保持一致；拒绝小于 1 毫秒（例如 500 微秒）或带有非整毫秒纳秒分量的 Duration。 */
  @Test
  void rejectsSubMillisecondAndNonWholeMillisecondDurations() {
    HarnessDispatcherProperties properties = new HarnessDispatcherProperties();

    Duration halfMillisecond = Duration.ofNanos(500_000);
    Duration oneAndHalfMillisecond = Duration.ofNanos(1_500_000);
    Duration nonWholeMillisecond = Duration.ofMillis(10).plusNanos(1);

    assertThrows(
        IllegalArgumentException.class, () -> properties.setLeaseDuration(halfMillisecond));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setLeaseDuration(oneAndHalfMillisecond));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setLeaseDuration(nonWholeMillisecond));

    assertThrows(IllegalArgumentException.class, () -> properties.setPollInterval(halfMillisecond));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setPollInterval(oneAndHalfMillisecond));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setPollInterval(nonWholeMillisecond));

    assertThrows(
        IllegalArgumentException.class, () -> properties.setRejectionDelay(halfMillisecond));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setRejectionDelay(oneAndHalfMillisecond));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setRejectionDelay(nonWholeMillisecond));
  }
}
