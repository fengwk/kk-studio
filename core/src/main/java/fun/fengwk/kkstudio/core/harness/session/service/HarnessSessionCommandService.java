package fun.fengwk.kkstudio.core.harness.session.service;

import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionMessageCreateDTO;

/** T15 harness session command service: create-root and submit-user-message. */
public interface HarnessSessionCommandService {

  HarnessSessionDTO createRootSession(HarnessSessionCreateDTO createDTO);

  HarnessSessionEntryDTO submitUserMessage(
      String sessionId, HarnessSessionMessageCreateDTO createDTO);
}
