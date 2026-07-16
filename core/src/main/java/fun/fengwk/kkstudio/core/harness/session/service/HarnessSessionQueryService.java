package fun.fengwk.kkstudio.core.harness.session.service;

import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import java.util.List;

/** T15 harness session query service. */
public interface HarnessSessionQueryService {

    HarnessSessionDTO getSession(String sessionId);

    List<HarnessSessionEntryDTO> listEntries(String sessionId);
}
