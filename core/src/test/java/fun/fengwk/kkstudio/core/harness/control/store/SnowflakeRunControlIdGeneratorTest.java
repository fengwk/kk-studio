package fun.fengwk.kkstudio.core.harness.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import org.junit.jupiter.api.Test;

/** 直接调用 {@link SnowflakeRunControlIdGenerator} 验证它分配的 id 与 Run id 来自独立 namespace。 */
class SnowflakeRunControlIdGeneratorTest {

  @Test
  void producesPositiveAndDistinctIds() {
    SnowflakeRunControlIdGenerator generator = new SnowflakeRunControlIdGenerator();
    long a = generator.newControlMessageId();
    long b = generator.newControlMessageId();
    assertTrue(a > 0);
    assertTrue(b > 0);
    assertNotEquals(a, b);
  }

  /**
   * {@link AgentIdGenerator#HARNESS_RUN_CONTROL_MESSAGE} 必须独立于 Run namespace，避免与 Run 主键共享同一生成来源。
   */
  @Test
  void controlMessageNamespaceIsDistinctFromRunNamespace() {
    assertNotEquals(
        AgentIdGenerator.HARNESS_RUN, AgentIdGenerator.HARNESS_RUN_CONTROL_MESSAGE);
    assertNotEquals(
        AgentIdGenerator.HARNESS_RUN_EVENT, AgentIdGenerator.HARNESS_RUN_CONTROL_MESSAGE);
    assertEquals("harness_run_control_message", AgentIdGenerator.HARNESS_RUN_CONTROL_MESSAGE);
  }
}