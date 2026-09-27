package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSession;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 产品 owner 共享的 Harness 命令接受事务边界：唯一的产品 Session 归属入口。
 *
 * <p>一个 Spring 物理事务内完成：先按 target 完成 owner 授权（Chat 要求目标 Session 由 {@code chat_session}
 * 持有；Issue+Agent 要求稳定 Thread 绑定 {@code (issueId, agentName) -> threadId} 存在且目标 Session 就是该 Thread 的
 * Session），再调用 {@link HarnessRuntime#acceptCommands} 把 Runtime store（同一
 * DataSource、PROPAGATION_REQUIRED）加入同一事务。所有 target 都会在 Runtime 的 preflight 中把 USER_MESSAGE 的瞬时
 * ATTACHMENT 物化为 durable RESOURCE 并维护 Session blob ref，同时只允许 RESOURCE 复用目标 Session 已拥有的 ref。精确
 * replay 时 Runtime 不调用 preflight，因此不会重复建立归属、不会重复消费 upload；但本服务在调用 Runtime 前仍完成 owner 授权，不能借 replay
 * 绕过归属。
 *
 * <p>首次创建的写入顺序与设计 §7.1 一致：调用方先持有产品锁并完成额度检查，再由 Harness NEW_SESSION 在同一事务内创建 Session/ROOT/Thread
 * 并接受初始命令；Chat 归属边由本服务的 preflight 与这些 Harness 写入一起提交，Issue+Agent 的稳定 Thread 绑定则必须由调用方在 {@code
 * accept} 返回后、同一物理事务内写入（此时 harness Thread 已存在，即时 FK 自然成立，不需要延迟 FK 或预创建空会话）。 本服务不代写 Issue+Agent
 * 绑定，也不提供任何“先绑定后接受”的历史顺序或兼容包装。
 *
 * <p>绑定一旦存在，它就是该归属唯一的授权依据且不可重绑：后续 THREAD/NEW_THREAD 接受必须已有稳定绑定，绑定 Thread 解析出的 Session 必须与 目标
 * Session 一致；同一 Thread 不能被另一个 Agent/Issue 抢走（唯一键拒绝），同一 Agent 也不能改绑到新 Thread。首次 NEW_SESSION 允许
 * 尚无绑定（会话与绑定由调用方在同一事务中原子提交），但目标 Session 一旦已存在就必须由该身份持有，否则 fail closed。
 *
 * <p>owner 正常请求使用 KEY SHARE（Chat 行）或共享/更新锁（Project SHARE → Issue UPDATE，不阻塞同 owner 的并发接受），owner
 * 删除路径由 {@link SessionDeletionOrchestrator} 走排他锁；本服务绝不暴露 Chat 专属 createThread/submitCommands
 * 形态的便利方法。
 */
@Service
public class HarnessCommandAcceptanceOrchestrator {

  private final ChatSessionRepository chatSessionRepository;
  private final ChatRepository chatRepository;
  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final ObjectProvider<HarnessStore> stores;
  private final ObjectProvider<HarnessRuntime> runtimes;
  private final StorageUploadService uploadService;
  private final SessionBlobRefManager refManager;
  private final StorageBlobManager blobManager;

  public HarnessCommandAcceptanceOrchestrator(
      ChatSessionRepository chatSessionRepository,
      ChatRepository chatRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueAgentThreadRepository issueAgentThreadRepository,
      ObjectProvider<HarnessStore> stores,
      ObjectProvider<HarnessRuntime> runtimes,
      StorageUploadService uploadService,
      SessionBlobRefManager refManager,
      StorageBlobManager blobManager) {
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.chatRepository = Objects.requireNonNull(chatRepository, "chatRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.stores = Objects.requireNonNull(stores, "stores");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  /** 接受 owner 的一次命令批：授权 + 归属/附件物化 + Runtime 入队在同一事务内原子完成。 */
  @Transactional
  public AcceptedCommands accept(OwnerRef owner, AcceptCommandsCommand command) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(command, "command");
    HarnessRuntime runtime = requireRuntime();
    requireNoGoalCommand(owner, command);
    authorize(owner, command.target());
    AcceptancePreflight preflight = preflight(owner, command.target());
    return runtime.acceptCommands(command, preflight);
  }

  /**
   * Issue Agent Thread 的 Issue 当前要求与活动本身才是权威，不得另设 Branch Goal：任何 Issue+Agent owner 的 GOAL
   * 命令（设置或清除）都在加锁与调用 Runtime 之前确定性拒绝。
   *
   * <p>这是安全边界，不依赖前端隐藏入口；必须保留该检查，否则产品 HTTP 入口可以绕过 Issue 的权威要求写入 Goal。
   */
  private static void requireNoGoalCommand(OwnerRef owner, AcceptCommandsCommand command) {
    if (!(owner instanceof OwnerRef.IssueAgent)) {
      return;
    }
    for (NewThreadCommand newThreadCommand : command.commands()) {
      if (newThreadCommand.payload().type() == ThreadCommandType.GOAL) {
        throw new IllegalArgumentException("Issue agent threads do not support branch goals");
      }
    }
  }

  /**
   * 调用 Runtime 前完成 owner 授权：NEW_SESSION 对新 Session 只要求 owner 存在且身份可绑定，但对已有 Session（包括 Runtime 精确
   * replay）要求目标 Session 已由该 owner 持有；NEW_THREAD/THREAD 始终要求目标 Session 已由该 owner 持有。
   */
  private void authorize(OwnerRef owner, AcceptCommandsTarget target) {
    switch (owner) {
      case OwnerRef.Chat chat -> {
        lockChatForKeyShare(chat.chatId());
        requireTargetOwnership(owner, target);
      }
      case OwnerRef.IssueAgent issueAgent -> {
        IssueAgentThread binding = lockIssueAndReadBinding(issueAgent);
        if (target instanceof AcceptCommandsTarget.NewSession newSession) {
          requireBindingAllowsNewSession(binding, newSession);
        }
        requireTargetOwnership(owner, target);
      }
    }
  }

  /**
   * 目标 Session 必须已由 owner 持有：NEW_SESSION 只在 Session 已存在（精确 replay）时校验；NEW_THREAD 直接用 target 的
   * sessionId； THREAD 在同一外事务内读取 Thread 的 Session 归属。
   */
  private void requireTargetOwnership(OwnerRef owner, AcceptCommandsTarget target) {
    switch (target) {
      case AcceptCommandsTarget.NewSession newSession -> {
        if (sessionExists(newSession.sessionId())) {
          requireOwnedSession(owner, newSession.sessionId());
        }
      }
      case AcceptCommandsTarget.NewThread newThread -> requireOwnedSession(
          owner, newThread.sessionId());
      case AcceptCommandsTarget.Thread thread -> requireOwnedSession(
          owner, findThreadSessionId(thread.threadId()));
    }
  }

  /**
   * NEW_SESSION 对 Issue+Agent owner 的额外约束：已有绑定只能精确指向 target Thread；同一 Agent 不允许改绑到新 Thread（绑定不可重绑）。
   */
  private static void requireBindingAllowsNewSession(
      IssueAgentThread binding, AcceptCommandsTarget.NewSession newSession) {
    if (binding != null && !binding.threadId().equals(newSession.threadId())) {
      throw new IllegalArgumentException("Issue agent is already bound to a different thread");
    }
  }

  /** Session 已存在时，NEW_SESSION 只能作为同 owner 的精确 replay，内部无归属 Session 也不得被产品 owner 接管。 */
  private boolean sessionExists(UUID sessionId) {
    return requireStore().transaction(tx -> tx.findSession(sessionId).isPresent());
  }

  /** THREAD target 不携带 sessionId：在同一外事务内读取其 Session 归属以完成授权。 */
  private UUID findThreadSessionId(UUID threadId) {
    return requireStore()
        .transaction(tx -> tx.findThread(threadId).map(ThreadState::sessionId))
        .orElseThrow(() -> new IllegalArgumentException("Thread does not exist"));
  }

  /** Chat owner 行以 KEY SHARE 锁定：阻止 Chat 删除但允许同 Chat 的并发接受；缺失即归属目标不存在。 */
  private void lockChatForKeyShare(UUID chatId) {
    if (chatRepository.lockForKeyShare(chatId) == null) {
      throw new IllegalArgumentException("Chat owner does not exist");
    }
  }

  /**
   * Issue+Agent owner 按 {@code Project SHARE -> Issue UPDATE} 锁序锁定产品层级，并读取稳定 Thread 绑定。
   *
   * <p>Project/Issue 缺失或已归档都确定性拒绝；绑定允许为空，仅表示该身份尚未完成首次接受：此时只有全新 NEW_SESSION（目标 Session
   * 尚不存在）能通过，且调用方必须在接受后于同一物理事务内写入绑定（设计 §7.1）。
   */
  private IssueAgentThread lockIssueAndReadBinding(OwnerRef.IssueAgent owner) {
    Issue issue = issueRepository.getById(owner.issueId());
    if (issue == null) {
      throw new IllegalArgumentException("Issue owner does not exist");
    }
    UUID projectId = issue.getProjectId();
    Project project = projectRepository.lockForKeyShare(projectId);
    if (project == null) {
      throw new IllegalArgumentException("Project owner does not exist");
    }
    if (project.isArchived()) {
      throw new IllegalArgumentException("Cannot accept commands for archived project");
    }
    Issue lockedIssue = issueRepository.lockById(issue.getId());
    if (lockedIssue == null || !lockedIssue.getProjectId().equals(projectId)) {
      throw new IllegalArgumentException("Issue owner hierarchy is inconsistent");
    }
    if (lockedIssue.isArchived()) {
      throw new IllegalArgumentException("Cannot accept commands for archived issue");
    }
    return issueAgentThreadRepository.findByIssueIdAndAgentName(
        lockedIssue.getId(), owner.agentName());
  }

  /**
   * 归属校验：目标 Session 必须已由该 owner 持有。
   *
   * <p>Issue+Agent 的 Session 只能由稳定 Thread 解析，绑定缺失或 Thread 不可解析都 fail closed，不能凭 sessionId 猜归属。
   */
  private void requireOwnedSession(OwnerRef owner, UUID sessionId) {
    switch (owner) {
      case OwnerRef.Chat chat -> {
        ChatSession relation = chatSessionRepository.findBySessionId(sessionId);
        if (relation == null || !relation.chatId().equals(chat.chatId())) {
          throw new IllegalArgumentException("Session ownership is inconsistent");
        }
      }
      case OwnerRef.IssueAgent issueAgent -> {
        IssueAgentThread binding =
            issueAgentThreadRepository.findByIssueIdAndAgentName(
                issueAgent.issueId(), issueAgent.agentName());
        if (binding == null || !sessionId.equals(boundSessionId(binding))) {
          throw new IllegalArgumentException("Session ownership is inconsistent");
        }
      }
    }
  }

  /** 由稳定绑定解析其 Thread 的 Session；Thread 不可解析说明归属事实已不一致，失败而不是回退。 */
  private UUID boundSessionId(IssueAgentThread binding) {
    return requireStore()
        .transaction(tx -> tx.findThread(binding.threadId()).map(ThreadState::sessionId))
        .orElseThrow(() -> new IllegalArgumentException("Issue agent thread does not exist"));
  }

  /**
   * 全新接受专用 preflight：NEW_SESSION 且 owner 为 Chat 时建立 Chat 归属，并对所有 target 物化 USER_MESSAGE 附件。精确
   * replay 时 Runtime 不调用本回调，因此不会产生重复副作用。
   *
   * <p>Issue+Agent 不在此写稳定 Thread 绑定：设计 §7.1 要求先由 Harness NEW_SESSION 创建 Session/ROOT/Thread
   * 与初始命令，再由调用方在同一 物理事务内写入绑定，本服务保持该顺序而不代写。
   */
  private AcceptancePreflight preflight(OwnerRef owner, AcceptCommandsTarget target) {
    return (tx, session, commands) -> {
      if (owner instanceof OwnerRef.Chat chat
          && target instanceof AcceptCommandsTarget.NewSession) {
        createChatSessionOwnership(session.id(), chat.chatId());
      }
      return prepareUserContents(session.id(), commands);
    };
  }

  /** 建立 Chat 归属边 {@code chat_session(session_id, chat_id)}：与 Runtime 写入同事务，唯一键原子保证 Session 单归属。 */
  private void createChatSessionOwnership(UUID sessionId, UUID chatId) {
    boolean inserted;
    try {
      inserted = chatSessionRepository.insert(sessionId, chatId);
    } catch (DataIntegrityViolationException e) {
      throw new IllegalStateException("Session is already owned");
    }
    if (!inserted) {
      throw new IllegalStateException("Session is already owned");
    }
  }

  /**
   * 准备 USER_MESSAGE 内容：瞬时 ATTACHMENT(uploadId) 物化为 durable RESOURCE，已有 RESOURCE 校验 Session
   * ownership；保持 idempotencyKey/requestHash 不变。
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
   * 消费一个 READY upload：锁定上传行（PENDING / 过期 / 不存在确定性拒绝）、以上传行权威文件名与权威媒体类型构造 RESOURCE、写 session blob
   * ref（retain 先于 release）、标记已消费上传 cleanup（release 其引用）。全部在同一外事务内，失败整体回滚；S3 清理由提交后的 Storage
   * Maintenance 完成。
   */
  private ResourceMessageContent consumeAttachment(
      UUID sessionId, AttachmentMessageContent attachment) {
    UUID uploadId = attachment.uploadId();
    StorageUploadService.ReadyUpload ready;
    try {
      ready = uploadService.lockReady(uploadId);
    } catch (StorageResourceNotFoundException | StorageVerificationException error) {
      throw new IllegalArgumentException("Attachment upload cannot be consumed");
    }
    refManager.retainRef(sessionId, ready.blobId());
    uploadService.delete(uploadId);
    return ResourceMessageContent.media(
        ready.blobId(),
        ready.filename(),
        null,
        imageTierForBlob(ready.blobId(), attachment.imageTier()));
  }

  /** RESOURCE 只复用当前 Session 已持有的 durable ref，不新增 retain，也不信任跨 Session blob id。 */
  private ResourceMessageContent requireOwnedResource(
      UUID sessionId, ResourceMessageContent resource) {
    if (!refManager.contains(sessionId, resource.blobId())) {
      throw new IllegalArgumentException("Resource is not owned by the current session");
    }
    if (resource.imageTier() == null) {
      return resource;
    }
    ImageInputTier imageTier = imageTierForBlob(resource.blobId(), resource.imageTier());
    if (imageTier == resource.imageTier()) {
      return resource;
    }
    return ResourceMessageContent.media(
        resource.blobId(), resource.name(), resource.preview(), imageTier);
  }

  /**
   * 只有图片媒体真正使用档位：wire 上的档位对 ATTACHMENT/RESOURCE 缺省即 720P，非图片媒体在这里收敛为 null，绝不持久化 无意义的档位（音频、视频、PDF
   * 与外部化文本都不携带档位）。
   */
  private ImageInputTier imageTierForBlob(UUID blobId, ImageInputTier requested) {
    StorageBlob blob = blobManager.getBlob(blobId);
    if (blob == null
        || blob.getState() != StorageBlobState.ACTIVE
        || blob.getMediaType() == null
        || !blob.getMediaType().startsWith("image/")) {
      return null;
    }
    return requested == null ? ImageInputTier.P720 : requested;
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
