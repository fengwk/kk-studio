package fun.fengwk.kkstudio.web.ai.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadCommandService;
import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.util.List;
import java.util.UUID;

/**
 * 无 S3 部署（Storage 未启用）下的 Chat Thread 命令契约：纯文本 create/submit 正常工作；附件提交确定性失败且不产生任何 durable 状态（绝不半消费）。
 */
class ChatThreadCommandWithoutStorageTest extends WebPostgresTestSupport {

  @Autowired private ChatService chatService;
  @Autowired private ChatThreadCommandService chatThreadCommandService;

  private String chatId;

  @BeforeEach
  void setUpChat() {
    ChatCreateDTO create = new ChatCreateDTO();
    create.setTitle("no-storage-chat");
    create.setAgentName("default-assistant");
    chatId = chatService.createChat(create).getId();
  }

  @Test
  void pureTextBatchWorksWithoutStorage() {
    CreatedThread created = createThread();
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi"))));
    ThreadCommandBatch batch =
        new ThreadCommandBatch(
            created.thread().id(),
            created.thread().headEntryId(),
            created.thread().nextCommandSequence(),
            List.of(
                new NewThreadCommand(
                    payload,
                    UUID.randomUUID(),
                    ThreadCommandPayloadJsonCodec.requestHash(payload))));

    assertEquals(1, chatThreadCommandService.submitCommands(batch).size());
  }

  @Test
  void attachmentWithoutStorageFailsDeterministicallyAndLeavesNoTrace() {
    CreatedThread created = createThread();
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(
                AgentMessageRole.USER, List.of(new AttachmentMessageContent(UUID.randomUUID()))));
    ThreadCommandBatch batch =
        new ThreadCommandBatch(
            created.thread().id(),
            created.thread().headEntryId(),
            created.thread().nextCommandSequence(),
            List.of(
                new NewThreadCommand(
                    payload,
                    UUID.randomUUID(),
                    ThreadCommandPayloadJsonCodec.requestHash(payload))));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> chatThreadCommandService.submitCommands(batch));
    assertTrue(
        error.getMessage().contains("global storage is not enabled"),
        "actual: " + error.getMessage());
  }

  private CreatedThread createThread() {
    return chatThreadCommandService.createChatThread(
        chatId,
        new CreateThreadCommand(
            new BranchSettings(
                null,
                "default-assistant",
                new ModelSelection("stub", "acceptance-stub", "default"),
                List.of()),
            false));
  }
}
