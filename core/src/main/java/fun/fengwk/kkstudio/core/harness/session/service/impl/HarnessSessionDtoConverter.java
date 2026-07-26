package fun.fengwk.kkstudio.core.harness.session.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Maps harness session / entry persistence rows to share DTOs. */
@Component
public class HarnessSessionDtoConverter {

  /** Session 到 share DTO 的投影。 */
  public HarnessSessionDTO convert(Session source) {
    if (source == null) {
      return null;
    }
    HarnessSessionDTO target = new HarnessSessionDTO();
    target.setSessionId(HarnessIds.format(source.id()));
    target.setTitle(source.title());
    target.setParentSessionId(
        source.parentSessionId() == null ? null : HarnessIds.format(source.parentSessionId()));
    target.setParentInvocationId(
        source.parentInvocationId() == null
            ? null
            : HarnessIds.format(source.parentInvocationId()));
    if (source.parentSessionId() == null) {
      target.setRootSessionId(HarnessIds.format(source.id()));
      target.setDepth(0);
    }
    target.setCreateTime(LocalDateTime.ofInstant(source.createdAt(), ZoneOffset.UTC));
    target.setUpdateTime(LocalDateTime.ofInstant(source.updatedAt(), ZoneOffset.UTC));
    return target;
  }

  public HarnessSessionDTO convert(HarnessSessionDO source) {
    if (source == null) {
      return null;
    }
    HarnessSessionDTO target = new HarnessSessionDTO();
    target.setSessionId(HarnessIds.format(source.getId()));
    target.setTitle(source.getTitle());
    target.setParentSessionId(
        source.getParentSessionId() == null
            ? null
            : HarnessIds.format(source.getParentSessionId()));
    target.setParentInvocationId(
        source.getParentInvocationId() == null
            ? null
            : HarnessIds.format(source.getParentInvocationId()));
    if (source.getParentSessionId() == null) {
      target.setRootSessionId(HarnessIds.format(source.getId()));
      target.setDepth(0);
    }
    target.setCreateTime(toUtcLocal(source.getCreatedAt()));
    target.setUpdateTime(toUtcLocal(source.getUpdatedAt()));
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
    target.setCreateTime(toUtcLocal(source.getCreatedAt()));
    return target;
  }

  private static LocalDateTime toUtcLocal(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }
}
