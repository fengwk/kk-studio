package fun.fengwk.kkstudio.core.agent.runtime.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.run.repo.AgentRunRepository;
import fun.fengwk.kkstudio.core.agent.run.repo.impl.mapper.AgentRunMapper;
import fun.fengwk.kkstudio.core.agent.run.repo.impl.model.AgentRunDO;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;
import fun.fengwk.kkstudio.core.agent.session.service.AgentSessionService;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.agent.run.repo.AgentRunRepository;
import fun.fengwk.kkstudio.core.agent.run.repo.impl.mapper.AgentRunMapper;
import fun.fengwk.kkstudio.core.agent.run.repo.impl.model.AgentRunDO;
import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;
import fun.fengwk.kkstudio.core.agent.session.service.AgentSessionService;
import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 验证 StaleRunReconciler 在启动时把 stale run 标记为 failed，新鲜 run 不受影响。
 *
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
public class StaleRunReconcilerTest {

  @Autowired private AgentRunMapper agentRunMapper;

  @Autowired private AgentRunRepository agentRunRepository;

  @Autowired private AgentRunService agentRunService;

  @Autowired private AgentSessionService agentSessionService;

  @Autowired private StaleRunReconciler reconciler;

  @Test
  public void shouldMarkStaleRunsFailedAndLeaveFreshRunsAlone() {
    // 唯一 runId 避免与其它测试相互污染
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String staleRunId1 = "stale-running-" + suffix;
    String staleRunId2 = "stale-queued-" + suffix;
    String freshRunId = "fresh-running-" + suffix;

    // 准备 session
    AgentSessionCreateDTO createDTO = new AgentSessionCreateDTO();
    createDTO.setAgentName("default-assistant");
    createDTO.setTitle("reconciler-test-" + suffix);
    AgentSessionDTO session = agentSessionService.createSession(createDTO);
    String sessionId = session.getSessionId();

    // 准备 3 条 run：2 stale + 1 fresh
    LocalDateTime longAgo = LocalDateTime.now().minusHours(1);
    LocalDateTime justNow = LocalDateTime.now();
    insertRunDirectly(staleRunId1, sessionId, "running", longAgo);
    insertRunDirectly(staleRunId2, sessionId, "queued", longAgo);
    insertRunDirectly(freshRunId, sessionId, "running", justNow);

    // 触发 reconciler
    reconciler.reconcile();

    // 断言：stale 全部变 failed，fresh 不动
    AgentRunDTO staleRun1 = agentRunService.getRun(staleRunId1);
    assertNotNull(staleRun1);
    assertEquals("failed", staleRun1.getStatus(), "stale running run should be marked failed");

    AgentRunDTO staleRun2 = agentRunService.getRun(staleRunId2);
    assertNotNull(staleRun2);
    assertEquals("failed", staleRun2.getStatus(), "stale queued run should be marked failed");

    AgentRunDTO freshRun1 = agentRunService.getRun(freshRunId);
    assertNotNull(freshRun1);
    assertEquals("running", freshRun1.getStatus(), "fresh running run should not be touched");

    // 再次调用 reconcile 应该是幂等的：没有新 stale run
    List<AgentRun> stillStale =
        agentRunRepository.listStaleRuns(LocalDateTime.now().minusMinutes(30));
    assertTrue(
        stillStale.stream()
            .noneMatch(r -> r.getRunId().equals(staleRunId1) || r.getRunId().equals(staleRunId2)),
        "re-reconcile should not re-fail already-failed runs");
  }

  private void insertRunDirectly(
      String runId, String sessionId, String status, LocalDateTime updateTime) {
    AgentRunDO row = new AgentRunDO();
    row.setId(AgentIdGenerator.nextRunId());
    row.setRunId(runId);
    row.setSessionId(sessionId);
    row.setTriggerEventId("ev_seed");
    row.setStatus(status);
    row.setCreateTime(updateTime);
    row.setUpdateTime(updateTime);
    agentRunMapper.insertSelective(row);
  }
}
