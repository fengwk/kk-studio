package fun.fengwk.kkstudio.core.harness.run.service.impl;

import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunQueryService;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.share.model.HarnessRunDTO;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class HarnessRunQueryServiceImpl implements HarnessRunQueryService {

    private final HarnessRunMapper runMapper;
    private final HarnessRunDtoConverter converter;

    public HarnessRunQueryServiceImpl(HarnessRunMapper runMapper, HarnessRunDtoConverter converter) {
        this.runMapper = runMapper;
        this.converter = converter;
    }

    @Override
    public HarnessRunDTO getRun(String runId) {
        long parsed = HarnessIds.parsePositive(runId, "runId");
        return converter.convert(runMapper.find(parsed));
    }

    @Override
    public List<HarnessRunDTO> listRuns(String sessionId) {
        long parsed = HarnessIds.parsePositive(sessionId, "sessionId");
        return runMapper.listBySessionOrderByCreateTimeAsc(parsed).stream()
            .map(converter::convert)
            .collect(Collectors.toList());
    }
}
