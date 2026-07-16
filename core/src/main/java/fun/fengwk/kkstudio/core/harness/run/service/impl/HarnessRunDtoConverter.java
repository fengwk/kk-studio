package fun.fengwk.kkstudio.core.harness.run.service.impl;

import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.share.model.HarnessRunDTO;
import org.springframework.stereotype.Component;

/** Maps harness run persistence rows to share DTOs. */
@Component
public class HarnessRunDtoConverter {

  public HarnessRunDTO convert(HarnessRunDO source) {
    if (source == null) {
      return null;
    }
    HarnessRunDTO target = new HarnessRunDTO();
    target.setRunId(HarnessIds.format(source.getId()));
    target.setSessionId(HarnessIds.format(source.getSessionId()));
    target.setTriggerEntryId(HarnessIds.format(source.getTriggerEntryId()));
    target.setStatus(source.getStatus());
    target.setTurnIndex(source.getTurnIndex());
    target.setAttempt(source.getAttempt());
    target.setEventSequence(source.getEventSequence());
    target.setNextAttemptAt(source.getNextAttemptAt());
    target.setCancelRequestedAt(source.getCancelRequestedAt());
    target.setStartedAt(source.getStartedAt());
    target.setFinishedAt(source.getFinishedAt());
    target.setCreateTime(source.getCreateTime());
    target.setUpdateTime(source.getUpdateTime());
    return target;
  }
}
