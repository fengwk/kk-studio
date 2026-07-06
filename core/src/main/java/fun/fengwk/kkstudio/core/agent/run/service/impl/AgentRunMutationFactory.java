package fun.fengwk.kkstudio.core.agent.run.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextRunId;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;

import java.time.LocalDateTime;

/**
 * AgentRunMutationFactory 负责组装 run 写路径上的持久化对象。
 *
 * @author fengwk
 */
@Component
final class AgentRunMutationFactory {

  private static final String STATUS_QUEUED = "queued";

  AgentRun newQueuedRun(
      String runId, String sessionId, String triggerEventId, LocalDateTime createTime) {
    requireNonBlank(runId, "runId");
    requireNonBlank(sessionId, "sessionId");
    requireNonBlank(triggerEventId, "triggerEventId");
    requireNonNull(createTime, "createTime");

    AgentRun run = new AgentRun();
    run.setId(nextRunId());
    run.setRunId(runId);
    run.setSessionId(sessionId);
    run.setTriggerEventId(triggerEventId);
    run.setStatus(STATUS_QUEUED);
    run.setCreateTime(createTime);
    run.setUpdateTime(createTime);
    return run;
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
