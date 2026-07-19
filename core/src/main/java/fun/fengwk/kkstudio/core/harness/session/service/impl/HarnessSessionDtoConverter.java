package fun.fengwk.kkstudio.core.harness.session.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;

/** Maps harness session / entry persistence rows to share DTOs. */
@Component
public class HarnessSessionDtoConverter {

  public HarnessSessionDTO convert(HarnessSessionDO source) {
    if (source == null) {
      return null;
    }
    HarnessSessionDTO target = new HarnessSessionDTO();
    target.setSessionId(HarnessIds.format(source.getId()));
    target.setTitle(source.getTitle());
    target.setMainThreadId(HarnessIds.format(source.getMainThreadId()));
    target.setRootSessionId(HarnessIds.format(source.getRootSessionId()));
    target.setParentSessionId(
        source.getParentSessionId() == null
            ? null
            : HarnessIds.format(source.getParentSessionId()));
    target.setParentInvocationId(
        source.getParentInvocationId() == null
            ? null
            : HarnessIds.format(source.getParentInvocationId()));
    target.setDepth(source.getDepth());
    target.setCreateTime(source.getCreateTime());
    target.setUpdateTime(source.getUpdateTime());
    return target;
  }

  public HarnessSessionEntryDTO convert(HarnessSessionEntryDO source) {
    if (source == null) {
      return null;
    }
    HarnessSessionEntryDTO target = new HarnessSessionEntryDTO();
    target.setEntryId(HarnessIds.format(source.getId()));
    target.setSessionId(HarnessIds.format(source.getSessionId()));
    target.setParentEntryId(
        source.getParentEntryId() == null ? null : HarnessIds.format(source.getParentEntryId()));
    target.setEntryType(source.getEntryType());
    target.setPayloadJson(source.getPayloadJson());
    target.setCreateTime(source.getCreateTime());
    return target;
  }
}
