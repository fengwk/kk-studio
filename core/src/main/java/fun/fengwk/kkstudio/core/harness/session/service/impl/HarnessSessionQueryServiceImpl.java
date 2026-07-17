package fun.fengwk.kkstudio.core.harness.session.service.impl;

import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class HarnessSessionQueryServiceImpl implements HarnessSessionQueryService {

  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final HarnessSessionDtoConverter converter;

  public HarnessSessionQueryServiceImpl(
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper entryMapper,
      HarnessSessionDtoConverter converter) {
    this.sessionMapper = sessionMapper;
    this.entryMapper = entryMapper;
    this.converter = converter;
  }

  @Override
  public HarnessSessionDTO getSession(String sessionId) {
    long parsed = HarnessIds.parsePositive(sessionId, "sessionId");
    HarnessSessionDO row = sessionMapper.find(parsed);
    if (row == null) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
    return converter.convert(row);
  }

  @Override
  public List<HarnessSessionEntryDTO> listEntries(String sessionId) {
    long parsed = HarnessIds.parsePositive(sessionId, "sessionId");
    HarnessSessionDO row = sessionMapper.find(parsed);
    if (row == null) {
      throw new IllegalArgumentException("session not found: " + sessionId);
    }
    // Skip the MysqlHarnessSessionStore facade path which requires a leaf; the entries endpoint
    // returns the entire entry timeline for the session, ordered by entry id ascending.
    List<HarnessSessionEntryDO> rows = entryMapper.listBySession(parsed);
    return rows.stream().map(converter::convert).collect(Collectors.toList());
  }

  @Override
  public List<HarnessSessionDTO> listRootSessions() {
    List<HarnessSessionDO> rows = sessionMapper.listRoots();
    return rows.stream().map(converter::convert).collect(Collectors.toList());
  }
}
