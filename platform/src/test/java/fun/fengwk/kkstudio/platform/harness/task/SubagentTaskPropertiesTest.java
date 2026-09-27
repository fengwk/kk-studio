package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.harness.configuration.SubagentTaskProperties;

import java.time.Duration;

/**
 * 验证异步 task 结算扫描的部署级参数契约。
 *
 * <p>测试意图：这些值是进程启动边界（不进 SystemSettings/DTO），非法值必须在读取时就明确失败， 而不是让扫描以零间隔空转或取到不可能的批量大小。
 */
class SubagentTaskPropertiesTest {

  @Test
  void defaultsAreUsableAndSettersRoundTrip() {
    SubagentTaskProperties properties = new SubagentTaskProperties();
    assertEquals(
        SubagentTaskProperties.DEFAULT_SETTLEMENT_INTERVAL, properties.getSettlementInterval());
    assertEquals(
        SubagentTaskProperties.DEFAULT_SETTLEMENT_BATCH_SIZE, properties.getSettlementBatchSize());

    properties.setSettlementInterval(Duration.ofMillis(250));
    properties.setSettlementBatchSize(7);
    assertEquals(Duration.ofMillis(250), properties.getSettlementInterval());
    assertEquals(7, properties.getSettlementBatchSize());
    assertTrue(properties.getSettlementInterval().toMillis() > 0);
  }

  @Test
  void rejectsNonPositiveSettlementInterval() {
    SubagentTaskProperties properties = new SubagentTaskProperties();
    properties.setSettlementInterval(null);
    assertThrows(IllegalArgumentException.class, properties::getSettlementInterval);
    properties.setSettlementInterval(Duration.ZERO);
    assertThrows(IllegalArgumentException.class, properties::getSettlementInterval);
    properties.setSettlementInterval(Duration.ofSeconds(-1));
    assertThrows(IllegalArgumentException.class, properties::getSettlementInterval);
  }

  @Test
  void rejectsSettlementBatchSizeBelowOne() {
    SubagentTaskProperties properties = new SubagentTaskProperties();
    properties.setSettlementBatchSize(0);
    assertThrows(IllegalArgumentException.class, properties::getSettlementBatchSize);
    properties.setSettlementBatchSize(-5);
    assertThrows(IllegalArgumentException.class, properties::getSettlementBatchSize);
  }
}
