package fun.fengwk.kkstudio.core.studio;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.ai.chat.repo.ChatSession;
import fun.fengwk.kkstudio.core.ai.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.core.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.core.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.core.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.studio.canvas.CanvasSession;
import fun.fengwk.kkstudio.studio.canvas.CanvasSessionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Chat/Canvas 共享的 Harness 命令接受事务边界：唯一的产品用户归属入口。
 *
 * <p>一个 Spring 物理事务内完成：先按 target 完成 owner 授权（NEW_SESSION 确认 owner 存在；ENTRY/THREAD 确认目标 Session 已由该
 * owner 的归属边持有），再调用 {@link HarnessRuntime#acceptCommands} 把 Runtime store（同一
 * DataSource、PROPAGATION_REQUIRED）加入同一事务。仅在全新接受时调用内部 preflight：NEW_SESSION 原子插入 owner relation （锁
 * harness_session 行 + 检查另一张归属表），所有 target 把 USER_MESSAGE 的瞬时 ATTACHMENT 物化为 durable RESOURCE 并维护
 * Session blob ref，同时只允许 RESOURCE 复用目标 Session 已拥有的 ref。精确 replay 时 Runtime 不调用 preflight，因此不会重复
 * 插入归属、不会重复消费 upload；但本服务在调用 Runtime 前仍完成 owner 授权，不能借 replay 绕过归属。
 *
 * <p>owner 正常请求使用 KEY SHARE（不阻塞同 owner 的并发接受），owner 删除路径由 {@link HarnessSessionDeletionService}
 * 走排他锁；本服务绝不暴露 Chat 专属 createThread/submitCommands 或 Canvas first-send 形态的便利方法。
 */
@Service
public class StudioCommandAcceptanceService {

  private final ChatSessionRepository chatSessionRepository;
  private final CanvasSessionRepository canvasSessionRepository;
  private final ChatRepository chatRepository;
  private final CanvasDocumentMapper canvasDocumentMapper;
  private final ObjectProvider<HarnessStore> stores;
  private final ObjectProvider<HarnessRuntime> runtimes;
  private final ObjectProvider<StorageUploadService> uploadServices;
  private final ObjectProvider<SessionBlobRefManager> refManagers;

  public StudioCommandAcceptanceService(
      ChatSessionRepository chatSessionRepository,
      CanvasSessionRepository canvasSessionRepository,
      ChatRepository chatRepository,
      CanvasDocumentMapper canvasDocumentMapper,
      ObjectProvider<HarnessStore> stores,
      ObjectProvider<HarnessRuntime> runtimes,
      ObjectProvider<StorageUploadService> uploadServices,
      ObjectProvider<SessionBlobRefManager> refManagers) {
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.canvasSessionRepository =
        Objects.requireNonNull(canvasSessionRepository, "canvasSessionRepository");
    this.chatRepository = Objects.requireNonNull(chatRepository, "chatRepository");
    this.canvasDocumentMapper =
        Objects.requireNonNull(canvasDocumentMapper, "canvasDocumentMapper");
    this.stores = Objects.requireNonNull(stores, "stores");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.uploadServices = Objects.requireNonNull(uploadServices, "uploadServices");
    this.refManagers = Objects.requireNonNull(refManagers, "refManagers");
  }

  /** 接受 owner 的一次命令批：授权 + 归属/附件物化 + Runtime 入队在同一事务内原子完成。 */
  @Transactional
  public AcceptedCommands accept(StudioOwner owner, AcceptCommandsCommand command) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(command, "command");
    HarnessRuntime runtime = requireRuntime();
    authorize(owner, command.target());
    AcceptancePreflight preflight = preflight(owner, command.target());
    return runtime.acceptCommands(command, preflight);
  }

  /**
   * 调用 Runtime 前完成 owner 授权：NEW_SESSION 对新 Session 只要求 owner 存在，但对已有 Session（包括 Runtime 精确
   * replay）要求目标 Session 已由该 owner 持有；ENTRY/THREAD 始终要求目标 Session 已由该 owner 持有。
   */
  private void authorize(StudioOwner owner, AcceptCommandsTarget target) {
    switch (target) {
      case AcceptCommandsTarget.NewSession newSession -> {
        lockOwnerForKeyShare(owner);
        if (sessionExists(newSession.sessionId())) {
          requireOwnedSession(owner, newSession.sessionId());
        }
      }
      case AcceptCommandsTarget.Entry entry -> {
        lockOwnerForKeyShare(owner);
        requireOwnedSession(owner, entry.sessionId());
      }
      case AcceptCommandsTarget.Thread thread -> {
        lockOwnerForKeyShare(owner);
        requireOwnedSession(owner, findSessionId(thread.threadId()));
      }
    }
  }

  /** Session 已存在时，NEW_SESSION 只能作为同 owner 的精确 replay，内部无归属 Session 也不得被产品 owner 接管。 */
  private boolean sessionExists(UUID sessionId) {
    return requireStore().transaction(tx -> tx.findSession(sessionId).isPresent());
  }

  /** KEY SHARE 锁定 owner 行：阻止 owner 删除（排他锁等待）但允许同 owner 的并发接受。owner 缺失即归属目标不存在，确定性拒绝。 */
  private void lockOwnerForKeyShare(StudioOwner owner) {
    if (owner.type() == StudioOwnerType.CHAT) {
      if (chatRepository.lockForKeyShare(owner.id()) == null) {
        throw new IllegalArgumentException("chat " + owner.id() + " does not exist");
      }
    } else {
      if (canvasDocumentMapper.getByIdForKeyShare(owner.id()) == null) {
        throw new IllegalArgumentException("canvas " + owner.id() + " does not exist");
      }
    }
  }

  /** THREAD target 不携带 sessionId：在同一外事务内读取其 Session 归属以完成授权。 */
  private UUID findSessionId(UUID threadId) {
    return requireStore()
        .transaction(tx -> tx.findThread(threadId).map(ThreadState::sessionId))
        .orElseThrow(() -> new IllegalArgumentException("thread " + threadId + " does not exist"));
  }

  /** 归属校验：目标 Session 必须已由该 owner 的 relation 行持有（Chat/Canvas 互斥由 relation 唯一存在性保证）。 */
  private void requireOwnedSession(StudioOwner owner, UUID sessionId) {
    if (owner.type() == StudioOwnerType.CHAT) {
      ChatSession relation = chatSessionRepository.findBySessionId(sessionId);
      if (relation == null || !relation.chatId().equals(owner.id())) {
        throw new IllegalArgumentException("session " + sessionId + " is not owned by " + owner);
      }
    } else {
      CanvasSession relation = canvasSessionRepository.findBySessionId(sessionId);
      if (relation == null || !relation.canvasId().equals(owner.id())) {
        throw new IllegalArgumentException("session " + sessionId + " is not owned by " + owner);
      }
    }
  }

  /**
   * 全新接受专用 preflight：NEW_SESSION 时插入 owner relation（锁 Session 行 + 检查另一张归属表），并对所有 target 物化
   * USER_MESSAGE 附件。精确 replay 时 Runtime 不调用本回调，因此不会产生重复副作用。
   */
  private AcceptancePreflight preflight(StudioOwner owner, AcceptCommandsTarget target) {
    return (tx, session, commands) -> {
      if (target instanceof AcceptCommandsTarget.NewSession) {
        createOwnership(tx, session.id(), owner);
      }
      return prepareUserContents(session.id(), commands);
    };
  }

  /**
   * 归属创建：单条 SQL 内原子地确认另一归属方不持有该 Session 后插入归属边（互斥），与 Runtime 的 Session/Thread/Command 写入
   * 处于同一事务，任何失败整体回滚。
   *
   * <p>preflight 在 Runtime 建 Thread（占 THREAD 锁序位）之后执行，本事务已无法再取 harness SESSION 锁 （SESSION -&gt;
   * THREAD 锁序不容回退），因此单归属互斥由 {@code insertIfNotOwnedByOther} 的单条语句原子性与 {@code harness_session}
   * 主键唯一性共同保证，而非额外的行锁。
   */
  private void createOwnership(HarnessStore.Transaction tx, UUID sessionId, StudioOwner owner) {
    int inserted =
        owner.type() == StudioOwnerType.CHAT
            ? chatSessionRepository.insertIfNotOwnedByOther(sessionId, owner.id())
            : canvasSessionRepository.insertIfNotOwnedByOther(sessionId, owner.id());
    if (inserted == 0) {
      throw new IllegalStateException(
          "session " + sessionId + " is already owned by the other studio kind");
    }
    if (inserted != 1) {
      throw new IllegalStateException("unexpected ownership rows for session " + sessionId);
    }
  }

  /**
   * 准备 USER_MESSAGE 内容：瞬时 ATTACHMENT(uploadId) 物化为 durable RESOURCE，已有 RESOURCE 校验 Session
   * ownership；保持 clientCommandId/requestHash 不变。
   */
  private List<NewThreadCommand> prepareUserContents(
      UUID sessionId, List<NewThreadCommand> commands) {
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
        } else if (content instanceof ResourceMessageContent resource) {
          contents.add(requireOwnedResource(sessionId, resource));
        } else {
          contents.add(content);
        }
      }
      prepared.add(
          command.withPayload(
              new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, contents))));
    }
    return List.copyOf(prepared);
  }

  /**
   * 消费一个 READY upload：锁定上传行（PENDING / 过期 / 不存在确定性拒绝）、以上传行权威文件名构造 RESOURCE、写 session blob ref
   * （retain 先于 release）、删除已消费上传行（release 其引用）。全部在同一外事务内，失败整体回滚。
   */
  private ResourceMessageContent consumeAttachment(
      UUID sessionId, AttachmentMessageContent attachment) {
    StorageUploadService uploadService = uploadServices.getIfAvailable();
    SessionBlobRefManager refManager = refManagers.getIfAvailable();
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

  /** RESOURCE 只复用当前 Session 已持有的 durable ref，不新增 retain，也不信任跨 Session blob id。 */
  private ResourceMessageContent requireOwnedResource(
      UUID sessionId, ResourceMessageContent resource) {
    SessionBlobRefManager refManager = refManagers.getIfAvailable();
    if (refManager == null) {
      throw new IllegalArgumentException(
          "global storage is not enabled; resource content is unavailable");
    }
    if (!refManager.contains(sessionId, resource.blobId())) {
      throw new IllegalArgumentException(
          "resource blob " + resource.blobId() + " is not owned by session " + sessionId);
    }
    return resource;
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }

  private HarnessStore requireStore() {
    HarnessStore store = stores.getIfAvailable();
    if (store == null) {
      throw new IllegalStateException("harness store is not available in this deployment");
    }
    return store;
  }
}
