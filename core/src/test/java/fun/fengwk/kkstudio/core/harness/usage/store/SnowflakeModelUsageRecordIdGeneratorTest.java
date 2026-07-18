package fun.fengwk.kkstudio.core.harness.usage.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;

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

  /** {@link AgentIdGenerator#MODEL_USAGE_RECORD} 必须独立于 Thread/Input/Event namespace。 */
  @Test
  void modelUsageRecordNamespaceIsDistinctFromExistingNamespaces() {
    assertNotEquals(AgentIdGenerator.HARNESS_THREAD, AgentIdGenerator.MODEL_USAGE_RECORD);
    assertNotEquals(AgentIdGenerator.HARNESS_THREAD_INPUT, AgentIdGenerator.MODEL_USAGE_RECORD);
    assertNotEquals(AgentIdGenerator.HARNESS_THREAD_EVENT, AgentIdGenerator.MODEL_USAGE_RECORD);
    assertEquals("model_usage_record", AgentIdGenerator.MODEL_USAGE_RECORD);
  }
}
