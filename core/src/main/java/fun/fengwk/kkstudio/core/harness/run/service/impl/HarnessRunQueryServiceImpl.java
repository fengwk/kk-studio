package fun.fengwk.kkstudio.core.harness.run.service.impl;

import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunQueryService;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.share.model.HarnessRunDTO;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class HarnessRunQueryServiceImpl implements HarnessRunQueryService {

  private final HarnessRunMapper runMapper;
  private final HarnessSessionMapper sessionMapper;
  private final HarnessRunDtoConverter converter;

  public HarnessRunQueryServiceImpl(
      HarnessRunMapper runMapper,
      HarnessSessionMapper sessionMapper,
      HarnessRunDtoConverter converter) {
    this.runMapper = runMapper;
    this.sessionMapper = sessionMapper;
    this.converter = converter;
  }

  @Override
  public HarnessRunDTO getRun(String runId) {
    long parsed = HarnessIds.parsePositive(runId, "runId");
    HarnessRunDO run = runMapper.find(parsed);
    if (run == null) {
      throw new IllegalArgumentException("unknown run: " + runId);
    }
    return converter.convert(run);
  }

  @Override
  public List<HarnessRunDTO> listRuns(String sessionId) {
    long parsed = HarnessIds.parsePositive(sessionId, "sessionId");
    if (sessionMapper.find(parsed) == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    return runMapper.listBySessionOrderByCreateTimeAsc(parsed).stream()
        .map(converter::convert)
        .collect(Collectors.toList());
  }
}
