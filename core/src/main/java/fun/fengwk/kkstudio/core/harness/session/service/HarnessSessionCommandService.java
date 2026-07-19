package fun.fengwk.kkstudio.core.harness.session.service;

import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;

/** Session 与 stable Main Thread 创建命令。 */
public interface HarnessSessionCommandService {
  HarnessSessionDTO createSession(HarnessSessionCreateDTO request);
}
