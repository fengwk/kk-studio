package fun.fengwk.kkstudio.core.agent.run.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;

/**
 * @author fengwk
 */
@Component
public class AgentRunConverter {

  public AgentRunDTO convert(AgentRun run) {
    if (run == null) {
      return null;
    }

    AgentRunDTO runDTO = new AgentRunDTO();
    runDTO.setRunId(run.getRunId());
    runDTO.setSessionId(run.getSessionId());
    runDTO.setTriggerEventId(run.getTriggerEventId());
    runDTO.setStatus(run.getStatus());
    runDTO.setCreateTime(run.getCreateTime());
    runDTO.setUpdateTime(run.getUpdateTime());
    return runDTO;
  }
}
