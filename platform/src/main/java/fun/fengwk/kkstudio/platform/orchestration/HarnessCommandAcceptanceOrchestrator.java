package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.canvas.CanvasSession;
import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
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
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSession;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionOwnershipRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 产品 owner 共享的 Harness 命令接受事务边界：唯一的产品 Session 归属入口。
 *
 * <p>一个 Spring 物理事务内完成：先按 target 完成 owner 授权（NEW_SESSION 确认 owner 存在；NEW_THREAD/THREAD 确认目标 Session
 * 已由该 owner 的归属边持有），再调用 {@link HarnessRuntime#acceptCommands} 把 Runtime store（同一
 * DataSource、PROPAGATION_REQUIRED）加入同一事务。仅在全新接受时调用内部 preflight：NEW_SESSION 插入 owner relation，由
 * {@code session_owner} 主键与排他弧约束原子保证三类 owner 全局互斥；所有 target 把 USER_MESSAGE 的瞬时 ATTACHMENT 物化为
 * durable RESOURCE 并维护 Session blob ref，同时只允许 RESOURCE 复用目标 Session 已拥有的 ref。精确 replay 时 Runtime
 * 不调用 preflight，因此不会重复插入归属、不会重复消费 upload；但本服务在调用 Runtime 前仍完成 owner 授权，不能借 replay 绕过归属。
 *
 * <p>owner 正常请求使用 KEY SHARE（不阻塞同 owner 的并发接受），owner 删除路径由 {@link SessionDeletionOrchestrator}
 * 走排他锁；本服务绝不暴露 Chat 专属 createThread/submitCommands 或 Canvas first-send 形态的便利方法。
 */
@Service
public class HarnessCommandAcceptanceOrchestrator {

  private final ChatSessionRepository chatSessionRepository;
  private final CanvasSessionRepository canvasSessionRepository;
  private final ChatRepository chatRepository;
  private final CanvasStore canvasStore;
  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueAgentSessionRepository issueAgentSessionRepository;
  private final IssueAgentSessionOwnershipRepository issueAgentSessionOwnershipRepository;
  private final ObjectProvider<HarnessStore> stores;
  private final ObjectProvider<HarnessRuntime> runtimes;
  private final StorageUploadService uploadService;
  private final SessionBlobRefManager refManager;

  public HarnessCommandAcceptanceOrchestrator(
      ChatSessionRepository chatSessionRepository,
      CanvasSessionRepository canvasSessionRepository,
      ChatRepository chatRepository,
      CanvasStore canvasStore,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueAgentSessionRepository issueAgentSessionRepository,
      IssueAgentSessionOwnershipRepository issueAgentSessionOwnershipRepository,
      ObjectProvider<HarnessStore> stores,
      ObjectProvider<HarnessRuntime> runtimes,
      StorageUploadService uploadService,
      SessionBlobRefManager refManager) {
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.canvasSessionRepository =
        Objects.requireNonNull(canvasSessionRepository, "canvasSessionRepository");
    this.chatRepository = Objects.requireNonNull(chatRepository, "chatRepository");
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueAgentSessionRepository =
        Objects.requireNonNull(issueAgentSessionRepository, "issueAgentSessionRepository");
    this.issueAgentSessionOwnershipRepository =
        Objects.requireNonNull(
            issueAgentSessionOwnershipRepository, "issueAgentSessionOwnershipRepository");
    this.stores = Objects.requireNonNull(stores, "stores");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
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
   * Issue Agent Branch 的 Issue 当前要求与活动本身才是权威，不得另设 Branch Goal：任何 owner 为 {@code
   * ISSUE_AGENT_SESSION} 的 GOAL 命令（设置或清除）都在加锁与调用 Runtime 之前确定性拒绝。
   *
   * <p>这是安全边界，不依赖前端隐藏入口；{@code DatabaseTurnResolver} 只是在该归属上不暴露 Goal 工具面。必须保留该检查， 否则产品 HTTP 入口可以绕过
   * Issue 的权威要求写入 Goal。
   */
  private static void requireNoGoalCommand(OwnerRef owner, AcceptCommandsCommand command) {
    if (owner.type() != OwnerType.ISSUE_AGENT_SESSION) {
      return;
    }
    for (NewThreadCommand newThreadCommand : command.commands()) {
      if (newThreadCommand.payload().type() == ThreadCommandType.GOAL) {
        throw new IllegalArgumentException(
            "Issue agent session branches do not support branch goals");
      }
    }
  }

  /**
   * 调用 Runtime 前完成 owner 授权：NEW_SESSION 对新 Session 只要求 owner 存在，但对已有 Session（包括 Runtime 精确
   * replay）要求目标 Session 已由该 owner 持有；NEW_THREAD/THREAD 始终要求目标 Session 已由该 owner 持有。
   */
  private void authorize(OwnerRef owner, AcceptCommandsTarget target) {
    switch (target) {
      case AcceptCommandsTarget.NewSession newSession -> {
        lockOwnerForKeyShare(owner);
        if (owner.type() == OwnerType.ISSUE_AGENT_SESSION) {
          IssueAgentSession binding = issueAgentSessionRepository.getById(owner.id());
          if (binding == null
              || !binding.getSessionId().equals(newSession.sessionId())
              || !binding.getThreadId().equals(newSession.threadId())) {
            throw new IllegalArgumentException(
                "Issue agent session is bound to a different session or branch");
          }
        }
        if (sessionExists(newSession.sessionId())) {
          requireOwnedSession(owner, newSession.sessionId());
        }
      }
      case AcceptCommandsTarget.NewThread newThread -> {
        lockOwnerForKeyShare(owner);
        requireOwnedSession(owner, newThread.sessionId());
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
  private void lockOwnerForKeyShare(OwnerRef owner) {
    switch (owner.type()) {
      case CHAT -> {
        if (chatRepository.lockForKeyShare(owner.id()) == null) {
          throw new IllegalArgumentException("Chat owner does not exist");
        }
      }
      case CANVAS -> {
        if (canvasStore.lockDocumentForKeyShare(owner.id()).isEmpty()) {
          throw new IllegalArgumentException("Canvas owner does not exist");
        }
      }
      case ISSUE_AGENT_SESSION -> {
        IssueAgentSession binding = issueAgentSessionRepository.getById(owner.id());
        if (binding == null) {
          throw new IllegalArgumentException("Issue agent session owner does not exist");
        }
        Issue issue = issueRepository.getById(binding.getIssueId());
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
        IssueAgentSession lockedBinding =
            issueAgentSessionRepository.findByIssueIdAndAgentName(
                lockedIssue.getId(), binding.getAgentName());
        if (lockedBinding == null
            || !lockedBinding.getId().equals(binding.getId())
            || !lockedBinding.getSessionId().equals(binding.getSessionId())) {
          throw new IllegalArgumentException("Issue agent session ownership is inconsistent");
        }
      }
    }
  }

  /** THREAD target 不携带 sessionId：在同一外事务内读取其 Session 归属以完成授权。 */
  private UUID findSessionId(UUID threadId) {
    return requireStore()
        .transaction(tx -> tx.findThread(threadId).map(ThreadState::sessionId))
        .orElseThrow(() -> new IllegalArgumentException("Thread does not exist"));
  }

  /** 归属校验：目标 Session 必须已由该 owner 的 relation 行持有。 */
  private void requireOwnedSession(OwnerRef owner, UUID sessionId) {
    switch (owner.type()) {
      case CHAT -> {
        ChatSession relation = chatSessionRepository.findBySessionId(sessionId);
        if (relation == null || !relation.chatId().equals(owner.id())) {
          throw new IllegalArgumentException("Session ownership is inconsistent");
        }
      }
      case CANVAS -> {
        CanvasSession relation = canvasSessionRepository.findBySessionId(sessionId);
        if (relation == null || !relation.canvasId().equals(owner.id())) {
          throw new IllegalArgumentException("Session ownership is inconsistent");
        }
      }
      case ISSUE_AGENT_SESSION -> {
        UUID agentSessionId =
            issueAgentSessionOwnershipRepository.findAgentSessionIdBySessionId(sessionId);
        if (agentSessionId == null || !agentSessionId.equals(owner.id())) {
          throw new IllegalArgumentException("Session ownership is inconsistent");
        }
      }
    }
  }

  /**
   * 全新接受专用 preflight：NEW_SESSION 时插入 owner relation，并对所有 target 物化 USER_MESSAGE 附件。精确 replay 时
   * Runtime 不调用本回调，因此不会产生重复副作用。
   */
  private AcceptancePreflight preflight(OwnerRef owner, AcceptCommandsTarget target) {
    return (tx, session, commands) -> {
      if (target instanceof AcceptCommandsTarget.NewSession) {
        createOwnership(session.id(), owner);
      }
      return prepareUserContents(session.id(), commands);
    };
  }

  /** 插入 owner relation；数据库主键原子保证三类 owner 互斥，且与 Runtime 写入同事务回滚。 */
  private void createOwnership(UUID sessionId, OwnerRef owner) {
    boolean bound;
    try {
      bound =
          switch (owner.type()) {
            case CHAT -> chatSessionRepository.insert(sessionId, owner.id());
            case CANVAS -> canvasSessionRepository.insert(sessionId, owner.id());
            case ISSUE_AGENT_SESSION -> issueAgentSessionOwnershipRepository.insert(
                sessionId, owner.id());
          };
    } catch (DataIntegrityViolationException e) {
      throw new IllegalStateException("Session is already owned or could not be bound");
    }
    if (!bound) {
      throw new IllegalStateException("Session is already owned or could not be bound");
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
   * 消费一个 READY upload：锁定上传行（PENDING / 过期 / 不存在确定性拒绝）、以上传行权威文件名构造 RESOURCE、写 session blob ref
   * （retain 先于 release）、标记已消费上传 cleanup（release 其引用）。全部在同一外事务内，失败整体回滚；S3 清理由提交后的 Storage
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
    return ResourceMessageContent.media(ready.blobId(), ready.filename());
  }

  /** RESOURCE 只复用当前 Session 已持有的 durable ref，不新增 retain，也不信任跨 Session blob id。 */
  private ResourceMessageContent requireOwnedResource(
      UUID sessionId, ResourceMessageContent resource) {
    if (!refManager.contains(sessionId, resource.blobId())) {
      throw new IllegalArgumentException("Resource is not owned by the current session");
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
