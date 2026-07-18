package fun.fengwk.kkstudio.core.harness.thread.service;

import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadYoloSetDTO;

/** Thread 创建、消息与设置入队（SET_YOLO / SET_AGENT）。 */
public interface HarnessThreadCommandService {
  HarnessThreadDTO createThread(HarnessThreadCreateDTO createDTO);

  HarnessThreadInputDTO submitUserMessage(String threadId, HarnessThreadMessageCreateDTO createDTO);

  /** 排队 SET_YOLO；Processor 在 turn 边界应用。 */
  HarnessThreadInputDTO queueYolo(String threadId, HarnessThreadYoloSetDTO request);

  /** 排队 SET_AGENT（解析冻结 snapshot）；Processor 在 turn 边界应用。 */
  HarnessThreadInputDTO queueAgent(String threadId, HarnessThreadAgentSetDTO request);
}
