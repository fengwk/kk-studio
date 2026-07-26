package fun.fengwk.kkstudio.core.harness.thread.service;

import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadBootstrapDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadBootstrapResultDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadYoloSetDTO;

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

  HarnessThreadStopResultDTO stop(String threadId, HarnessThreadStopDTO dto);
}
