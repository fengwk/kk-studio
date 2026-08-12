package fun.fengwk.kkstudio.core.ai.chat.service.impl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatThreadRepository;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadCommandService;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.core.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.core.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link ChatThreadCommandService} 的生产实现。
 *
 * <p>提交事务顺序（与 Runtime preflight 契约一致）：store 事务内先 lock Thread 并完成幂等 hash 重放检查，只有全新 batch 才进入 {@link
 * #materializeAttachments}：逐条消费 READY upload（行锁 + 权威文件名）→ session blob ref + retain （先于 upload
 * release）→ 删除已消费 upload 行并 release 其引用 → 由 Runtime 插入 durable command。所有步骤共享同一
 * 外层事务（READ_COMMITTED），任何失败整体回滚，重复请求不会重复消费 upload。
 *
 * <p>Harness Runtime 对 createChatThread / submitCommands 都是必选装配（任何创建或提交都经过 Runtime 的 store
 * 事务）；Storage 才是可选装配：纯文本 Chat 在 S3 缺失时仍可工作，附件消费路径在 Storage 缺失时确定性失败（绝不半消费）。
 */
@Service
public class ChatThreadCommandServiceImpl implements ChatThreadCommandService {

  private final ChatGuard chatGuard;
  private final ChatThreadRepository chatThreadRepository;
  private final ObjectProvider<HarnessRuntime> runtimeProvider;
  private final ObjectProvider<StorageUploadService> uploadServiceProvider;
  private final ObjectProvider<SessionBlobRefManager> refManagerProvider;

  public ChatThreadCommandServiceImpl(
      ChatGuard chatGuard,
      ChatThreadRepository chatThreadRepository,
      ObjectProvider<HarnessRuntime> runtimeProvider,
      ObjectProvider<StorageUploadService> uploadServiceProvider,
      ObjectProvider<SessionBlobRefManager> refManagerProvider) {
    this.chatGuard = Objects.requireNonNull(chatGuard, "chatGuard");
    this.chatThreadRepository =
        Objects.requireNonNull(chatThreadRepository, "chatThreadRepository");
    this.runtimeProvider = Objects.requireNonNull(runtimeProvider, "runtimeProvider");
    this.uploadServiceProvider =
        Objects.requireNonNull(uploadServiceProvider, "uploadServiceProvider");
    this.refManagerProvider = Objects.requireNonNull(refManagerProvider, "refManagerProvider");
  }

  @Override
  @Transactional
  public CreatedThread createChatThread(String chatId, CreateThreadCommand command) {
    Objects.requireNonNull(chatId, "chatId");
    Objects.requireNonNull(command, "command");
    HarnessRuntime runtime = requireRuntime();
    Chat chat = chatGuard.requireChat(chatId);
    // runtime.createThread 的 store 事务（PROPAGATION_REQUIRED）加入本应用事务；关联失败整体回滚。
    CreatedThread created = runtime.createThread(command);
    chatThreadRepository.associate(chat.getId(), created.thread().id());
    return created;
  }

  @Override
  @Transactional
  public List<ThreadCommand> submitCommands(ThreadCommandBatch batch) {
    Objects.requireNonNull(batch, "batch");
    return requireRuntime().enqueueCommands(batch, this::materializeAttachments);
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimeProvider.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }

  /**
   * Runtime preflight（store 事务内、幂等重放检查后调用）：把 USER_MESSAGE 的瞬时 ATTACHMENT 内容物化为 durable RESOURCE。保持
   * clientCommandId / requestHash 与内容顺序不变。Storage 缺失且 batch 含附件时确定性失败，整体回滚。
   */
  private List<NewThreadCommand> materializeAttachments(
      HarnessStore.Transaction tx, UUID sessionId, List<NewThreadCommand> commands) {
    List<NewThreadCommand> prepared = new ArrayList<>(commands.size());
    for (NewThreadCommand command : commands) {
      if (!(command.payload() instanceof UserMessageCommandPayload user)) {
        prepared.add(command);
        continue;
      }
      AgentMessage message = user.message();
      List<AgentMessageContent> contents = new ArrayList<>(message.contents().size());
      for (AgentMessageContent content : message.contents()) {
        if (content instanceof AttachmentMessageContent attachment) {
          contents.add(consumeAttachment(sessionId, attachment));
        } else {
          contents.add(content);
        }
      }
      prepared.add(
          new NewThreadCommand(
              new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, contents)),
              command.clientCommandId(),
              command.requestHash()));
    }
    return List.copyOf(prepared);
  }

  /**
   * 消费一个 READY upload：锁定上传行（PENDING / 过期 / 不存在确定性拒绝）、以上传行的权威文件名构造 RESOURCE、写入 session blob
   * ref（retain 先于 release）、删除已消费上传行（release 其引用）。全部在同一外事务内。
   */
  private ResourceMessageContent consumeAttachment(
      UUID sessionId, AttachmentMessageContent attachment) {
    StorageUploadService uploadService = uploadServiceProvider.getIfAvailable();
    SessionBlobRefManager refManager = refManagerProvider.getIfAvailable();
    if (uploadService == null || refManager == null) {
      throw new IllegalArgumentException(
          "global storage is not enabled; attachment content is unavailable");
    }
    UUID uploadId = attachment.uploadId();
    StorageUploadService.ReadyUpload ready;
    try {
      ready = uploadService.lockReady(uploadId);
    } catch (StorageResourceNotFoundException | StorageVerificationException error) {
      throw new IllegalArgumentException(
          "attachment upload " + uploadId + " cannot be consumed: " + error.getMessage(), error);
    }
    refManager.retainRef(sessionId, ready.blobId());
    uploadService.delete(uploadId);
    return new ResourceMessageContent(ready.blobId(), ready.filename(), null);
  }
}
