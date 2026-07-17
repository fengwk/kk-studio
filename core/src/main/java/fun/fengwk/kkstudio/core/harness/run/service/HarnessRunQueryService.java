package fun.fengwk.kkstudio.core.harness.run.service;

import fun.fengwk.kkstudio.share.model.HarnessRunDTO;

import java.util.List;

/** T15 harness run query service. */
public interface HarnessRunQueryService {

  HarnessRunDTO getRun(String runId);

  List<HarnessRunDTO> listRuns(String sessionId);
}
