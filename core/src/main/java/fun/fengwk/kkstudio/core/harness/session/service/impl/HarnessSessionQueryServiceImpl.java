package fun.fengwk.kkstudio.core.harness.session.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.harness.query.HarnessQueryDtoConverter;
import fun.fengwk.kkstudio.core.harness.query.HarnessQueryRow;
import fun.fengwk.kkstudio.core.harness.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;

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
    HarnessQueryRow session = requireSession(sessionId);
    return queryMapper.listEntriesBySession(session.getId()).stream()
        .map(converter::toEntry)
        .toList();
  }

  @Override
  public List<HarnessSessionDTO> listRootSessions() {
    return queryMapper.listRootSessions().stream().map(converter::toSession).toList();
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
