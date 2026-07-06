package fun.fengwk.kkstudio.core.agent.run.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;

import java.time.LocalDateTime;

/**
 * AgentRunMutationFactory 的聚焦行为测试。
 *
 * @author fengwk
 */
public class AgentRunMutationFactoryTest {

  /** 校验创建 queued run 时会补齐主键、状态和一致的时间戳。 */
  @Test
  public void shouldCreateQueuedRunWithNormalizedState() {
    AgentRunMutationFactory factory = new AgentRunMutationFactory();
    LocalDateTime now = LocalDateTime.of(2026, 7, 6, 20, 0);

    AgentRun run = factory.newQueuedRun("rn_test", "se_test", "ev_test", now);

    assertNotNull(run.getId());
    assertEquals("rn_test", run.getRunId());
    assertEquals("se_test", run.getSessionId());
    assertEquals("ev_test", run.getTriggerEventId());
    assertEquals("queued", run.getStatus());
    assertEquals(now, run.getCreateTime());
    assertEquals(now, run.getUpdateTime());
  }

  /** 校验缺失关键字段时会直接拒绝，避免写入半残 run 记录。 */
  @Test
  public void shouldRejectInvalidArguments() {
    AgentRunMutationFactory factory = new AgentRunMutationFactory();
    LocalDateTime now = LocalDateTime.now();

    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newQueuedRun(null, "se_test", "ev_test", now));
    assertThrows(
        IllegalArgumentException.class, () -> factory.newQueuedRun("rn_test", " ", "ev_test", now));
    assertThrows(
        IllegalArgumentException.class, () -> factory.newQueuedRun("rn_test", "se_test", "", now));
    assertThrows(
        IllegalArgumentException.class,
        () -> factory.newQueuedRun("rn_test", "se_test", "ev_test", null));
  }
}
