package fun.fengwk.kkstudio.core.ai.runtime.session.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.ai.runtime.query.HarnessQueryDtoConverter;
import fun.fengwk.kkstudio.core.ai.runtime.query.HarnessQueryRow;
import fun.fengwk.kkstudio.core.ai.runtime.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.core.ai.runtime.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.core.ai.runtime.session.support.HarnessIds;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;

import java.util.List;
import java.util.Objects;

/** final PostgreSQL Session read model。 */
@Service
public class HarnessSessionQueryServiceImpl implements HarnessSessionQueryService {

  private final PostgresqlHarnessQueryMapper queryMapper;
  private final HarnessQueryDtoConverter converter;

  public HarnessSessionQueryServiceImpl(
      PostgresqlHarnessQueryMapper queryMapper, HarnessQueryDtoConverter converter) {
    this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper");
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  @Override
  public HarnessSessionDTO getSession(String sessionId) {
    return converter.toSession(requireSession(sessionId));
  }

  @Override
  public List<HarnessSessionEntryDTO> listEntries(String sessionId) {
    long parsed = HarnessIds.parsePositive(sessionId, "sessionId");
    requireSession(sessionId);
    return queryMapper.listEntriesBySession(parsed).stream().map(converter::toEntry).toList();
  }

  @Override
  public List<HarnessSessionDTO> listSessions() {
    return queryMapper.listSessions().stream().map(converter::toSession).toList();
  }

  private HarnessQueryRow requireSession(String sessionId) {
    long parsed = HarnessIds.parsePositive(sessionId, "sessionId");
    HarnessQueryRow row = queryMapper.findSession(parsed);
    if (row == null) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
    return row;
  }
}
