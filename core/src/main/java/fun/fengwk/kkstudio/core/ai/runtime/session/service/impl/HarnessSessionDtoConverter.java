package fun.fengwk.kkstudio.core.ai.runtime.session.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.runtime.session.support.HarnessIds;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Maps runtime {@link Session} aggregates created as part of Thread bootstrap to the {@link
 * HarnessSessionDTO} returned by the bootstrap endpoint.
 */
@Component
public class HarnessSessionDtoConverter {

  /** Runtime Session → bootstrap response DTO 投影。 */
  public HarnessSessionDTO convert(Session source) {
    if (source == null) {
      return null;
    }
    HarnessSessionDTO target = new HarnessSessionDTO();
    target.setSessionId(HarnessIds.format(source.id()));
    target.setTitle(source.title());
    target.setCreateTime(LocalDateTime.ofInstant(source.createdAt(), ZoneOffset.UTC));
    target.setUpdateTime(LocalDateTime.ofInstant(source.createdAt(), ZoneOffset.UTC));
    return target;
  }
}
