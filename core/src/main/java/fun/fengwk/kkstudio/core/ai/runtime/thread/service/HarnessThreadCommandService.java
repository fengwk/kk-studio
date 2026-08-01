package fun.fengwk.kkstudio.core.ai.runtime.thread.service;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadBootstrapDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadBootstrapResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadEnvironmentSetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadYoloSetDTO;

/** Thread command boundary. */
public interface HarnessThreadCommandService {
  HarnessThreadDTO createThread();

  HarnessThreadBootstrapResultDTO bootstrapThread(String threadId, HarnessThreadBootstrapDTO dto);

  HarnessThreadDTO updateHead(String threadId, HarnessThreadHeadUpdateDTO dto);

  HarnessThreadInputDTO submitUserMessage(String threadId, HarnessThreadMessageCreateDTO dto);

  HarnessThreadInputDTO submitCustomMessage(
      String threadId, HarnessThreadCustomMessageCreateDTO dto);

  HarnessThreadInputDTO queueYolo(String threadId, HarnessThreadYoloSetDTO dto);

  HarnessThreadInputDTO queueAgent(String threadId, HarnessThreadAgentSetDTO dto);

  HarnessThreadInputDTO queueModel(String threadId, HarnessThreadModelSetDTO dto);

  HarnessThreadInputDTO queueEnvironment(String threadId, HarnessThreadEnvironmentSetDTO dto);

  HarnessThreadStopResultDTO stop(String threadId, HarnessThreadStopDTO dto);
}
