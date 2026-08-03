package fun.fengwk.kkstudio.core.ai.runtime.thread.service;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadEnvironmentUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;

/** Thread command boundary. */
public interface HarnessThreadCommandService {
  HarnessThreadDTO createThread(String title, String environmentName);

  HarnessThreadDTO updateHead(String threadId, HarnessThreadHeadUpdateDTO dto);

  HarnessThreadDTO updateEnvironment(String threadId, HarnessThreadEnvironmentUpdateDTO dto);

  HarnessThreadInputDTO submitUserMessage(String threadId, HarnessThreadMessageCreateDTO dto);

  HarnessThreadInputDTO submitCustomMessage(
      String threadId, HarnessThreadCustomMessageCreateDTO dto);

  HarnessThreadStopResultDTO stop(String threadId, HarnessThreadStopDTO dto);
}
