package fun.fengwk.kkstudio.core.harness.usage.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import org.junit.jupiter.api.Test;

/** 直接调用 {@link SnowflakeModelUsageRecordIdGenerator} 验证它分配的 id 与已有 namespace 互不冲突。 */
class SnowflakeModelUsageRecordIdGeneratorTest {

  @Test
  void producesPositiveAndDistinctIds() {
    SnowflakeModelUsageRecordIdGenerator generator = new SnowflakeModelUsageRecordIdGenerator();
    long a = generator.newModelUsageRecordId();
    long b = generator.newModelUsageRecordId();
    assertTrue(a > 0);
    assertTrue(b > 0);
    assertNotEquals(a, b);
  }

  /** {@link AgentIdGenerator#MODEL_USAGE_RECORD} 必须独立于 Run/Run Event/Control namespace，避免主键空间冲突。 */
  @Test
  void modelUsageRecordNamespaceIsDistinctFromExistingNamespaces() {
    assertNotEquals(AgentIdGenerator.HARNESS_RUN, AgentIdGenerator.MODEL_USAGE_RECORD);
    assertNotEquals(AgentIdGenerator.HARNESS_RUN_EVENT, AgentIdGenerator.MODEL_USAGE_RECORD);
    assertNotEquals(
        AgentIdGenerator.HARNESS_RUN_CONTROL_MESSAGE, AgentIdGenerator.MODEL_USAGE_RECORD);
    assertEquals("model_usage_record", AgentIdGenerator.MODEL_USAGE_RECORD);
  }
}
