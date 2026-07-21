package fun.fengwk.kkstudio.core.harness.thread.service;

import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadYoloSetDTO;

/** Session 内 branch Thread 创建和 typed mailbox 命令。 */
public interface HarnessThreadCommandService {
  HarnessThreadDTO createThread(String sessionId, HarnessThreadCreateDTO createDTO);

  HarnessThreadInputDTO submitUserMessage(String threadId, HarnessThreadMessageCreateDTO createDTO);

  HarnessThreadInputDTO submitCustomMessage(
      String threadId, HarnessThreadCustomMessageCreateDTO createDTO);

  /** 排队 SET_YOLO；Processor 在消息边界有序应用。 */
  HarnessThreadInputDTO queueYolo(String threadId, HarnessThreadYoloSetDTO request);

  /** 排队 SET_AGENT（仅 id/name）；Processor 应用时加载当前 AgentDefinition。 */
  HarnessThreadInputDTO queueAgent(String threadId, HarnessThreadAgentSetDTO request);

  HarnessThreadInputDTO queueModel(String threadId, HarnessThreadModelSetDTO request);

  HarnessThreadStopResultDTO stop(String threadId, HarnessThreadStopDTO request);
}
