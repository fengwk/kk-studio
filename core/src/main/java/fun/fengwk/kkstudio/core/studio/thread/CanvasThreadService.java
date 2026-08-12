package fun.fengwk.kkstudio.core.studio.thread;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas 根 Thread 的窄端口：首次发送原子创建并绑定根 Thread。
 *
 * <p>与 chat-attachments 分支的通用 {@code ChatThreadCommandService} 语义对齐：单一应用事务内完成创建 + 关联（本端口的 关联是
 * {@code canvas_document.thread_id}，对应通用服务的 Chat↔Thread associate），commandId 作为 {@code
 * clientCommandId} 幂等键；HarnessRuntime 为可选装配（缺失时只在首次发送调用点确定性失败）。本端口不感知 Chat 关联/附件消费（contents 只含
 * TEXT/IMAGE/AUDIO/VIDEO），也不复制任何通用附件代码；web 层复用 {@code
 * HarnessRuntimeWebMapper.toUserMessageContents} 把 share DTO 映射为本端口的 harness 领域类型。
 */
public interface CanvasThreadService {

  /** 首次发送结果：绑定后的 Thread id 与携带 threadId 的最新 document。 */
  record CanvasFirstSendResult(UUID threadId, CanvasDocument document) {
    public CanvasFirstSendResult {
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(document, "document");
    }
  }

  /**
   * 原子首次发送：同一事务内锁定 canvas_document 行、创建根 Thread（branch settings + YOLO policy）、 入队有序 USER_MESSAGE
   * contents（commandId 作为 clientCommandId 幂等键）并绑定 {@code document.thread_id}。已有绑定 Thread 时原样重放（返回既有
   * Thread 与 document，不重复发送）。
   */
  CanvasFirstSendResult sendFirstMessage(UUID canvasId, CanvasFirstSendCommand command);

  /** 首次发送请求。 */
  record CanvasFirstSendCommand(
      String commandId,
      BranchSettings branchSettings,
      boolean yoloEnabled,
      List<AgentMessageContent> contents) {

    public CanvasFirstSendCommand {
      if (commandId == null || commandId.isBlank()) {
        throw new IllegalArgumentException("commandId must not be blank");
      }
      Objects.requireNonNull(branchSettings, "branchSettings");
      Objects.requireNonNull(contents, "contents");
      contents = List.copyOf(contents);
      if (contents.isEmpty()) {
        throw new IllegalArgumentException("contents must not be empty");
      }
      contents.forEach(content -> Objects.requireNonNull(content, "contents[]"));
    }
  }
}
