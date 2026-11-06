package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 产品 owner 共享的 Harness 命令接受事务边界。
 *
 * <p>产品 SPI 按身份锁定并校验归属和允许的操作。Runtime 接受、首次归属写入与附件物化加入同一物理事务。精确重放不执行 preflight，但仍先校验归属。
 */
@Service
public class HarnessCommandAcceptanceOrchestrator {

  private final List<SessionOwnership> ownerships;
  private final ObjectProvider<HarnessRuntime> runtimes;
  private final StorageUploadService uploadService;
  private final SessionBlobRefManager refManager;
  private final UserMessageContentPreparer contentPreparer;

  public HarnessCommandAcceptanceOrchestrator(
      List<SessionOwnership> ownerships,
      ObjectProvider<HarnessRuntime> runtimes,
      StorageUploadService uploadService,
      SessionBlobRefManager refManager,
      StorageBlobManager blobManager) {
    this.ownerships = List.copyOf(ownerships);
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
    this.contentPreparer =
        new UserMessageContentPreparer(
            this.refManager,
            Objects.requireNonNull(blobManager, "blobManager"),
            this::consumeAttachment);
  }

  /** 接受一次 owner-aware 的命令批：授权 + 归属/附件物化 + Runtime 入队在同一事务内原子完成。 */
  @Transactional
  public AcceptedCommands accept(SessionOwner owner, AcceptCommandsCommand command) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(command, "command");
    requireCreationTarget(command.target());
    List<SessionOwnership> matches =
        ownerships.stream().filter(item -> owner.kind().equals(item.kind())).toList();
    if (matches.isEmpty()) {
      throw DomainException.validation(
          "owner", "Unknown or ambiguous session owner kind: " + owner.kind());
    }
    if (matches.size() != 1) {
      throw new IllegalStateException("Ambiguous session owner kind: " + owner.kind());
    }
    SessionOwnership ownership = matches.getFirst();
    if (!ownership.allowsDirectCommands()) {
      throw new DirectCommandsNotAllowedException();
    }
    HarnessRuntime runtime = requireRuntime();
    authorize(ownership, owner, command.target());
    AcceptancePreflight preflight = preflight(ownership, owner, command.target());
    return runtime.acceptCommands(command, preflight);
  }

  /**
   * 接受既有 Thread 的通用命令批（非 owner-aware）：target 由调用方从 path 与精确 cursor 组合。
   *
   * <p>Runtime 从 Thread 解析 Session，preflight 在同一事务内完成 USER_MESSAGE 附件物化与 RESOURCE 的 Session
   * 归属校验；owner 授权与业务规则由调用方自己的边界显式完成，本服务不改写目标 Thread 的所属事实。
   */
  @Transactional
  public AcceptedCommands acceptOnThread(AcceptCommandsCommand command) {
    Objects.requireNonNull(command, "command");
    if (!(command.target() instanceof AcceptCommandsTarget.Thread)) {
      throw new IllegalArgumentException("thread command acceptance requires a THREAD target");
    }
    AcceptancePreflight preflight =
        (session, commands, copiedEntries) -> prepareUserContents(session.id(), commands);
    return requireRuntime().acceptCommands(command, preflight);
  }

  /**
   * owner-aware 入口只服务产品创建（NEW_ROOT_SESSION / NEW_THREAD / NEW_FORKED_SESSION）：既有 Thread 的继续写入走
   * {@link #acceptOnThread}，子代理与任务只由 {@code AgentTaskService} 经 {@code HarnessRuntime.spawn}
   * 原子创建，不经产品 owner 路由（产品 owner 无法持有子 Session）。
   */
  private static void requireCreationTarget(AcceptCommandsTarget target) {
    if (!(target instanceof AcceptCommandsTarget.NewRootSession)
        && !(target instanceof AcceptCommandsTarget.NewThread)
        && !(target instanceof AcceptCommandsTarget.NewForkedSession)) {
      throw DomainException.validation(
          "target",
          "owner-aware command acceptance is limited to NEW_SESSION / NEW_THREAD /"
              + " NEW_FORKED_SESSION creation");
    }
  }

  /**
   * 调用 Runtime 前完成 owner 授权：NEW_SESSION 对新 Session 只要求 owner 存在且身份可绑定，但对已有 Session（包括 Runtime 精确
   * replay）要求目标 Session 已由该 owner 持有；NEW_THREAD 始终要求目标 Session 已由该 owner 持有。
   */
  private void authorize(
      SessionOwnership ownership, SessionOwner owner, AcceptCommandsTarget target) {
    UUID rootThreadId =
        target instanceof AcceptCommandsTarget.NewRootSession root ? root.threadId() : null;
    ownership.lockForAcceptance(owner, rootThreadId);
    requireTargetOwnership(ownership, owner, target);
  }

  /**
   * 目标 Session 必须已由 owner 持有：NEW_SESSION 只在 Session 已存在（精确 replay）时校验；NEW_THREAD 直接用 target 的
   * sessionId；NEW_FORKED_SESSION 先检查产品规则，再校验来源 Thread 所属 Session。既有 Thread 的继续写入不经 owner 授权 （{@link
   * #acceptOnThread}）；THREAD 不属于 owner-aware 产品入口，即使上层形状校验被绕过也必须 fail closed。
   */
  private void requireTargetOwnership(
      SessionOwnership ownership, SessionOwner owner, AcceptCommandsTarget target) {
    switch (target) {
      case AcceptCommandsTarget.NewRootSession newRootSession -> {
        if (sessionExists(newRootSession.sessionId())) {
          requireOwnedSession(ownership, owner, newRootSession.sessionId());
        }
      }
      case AcceptCommandsTarget.NewThread newThread -> requireOwnedSession(
          ownership, owner, newThread.sessionId());
      case AcceptCommandsTarget.NewForkedSession forkedSession -> {
        if (!ownership.allowsFork()) {
          throw DomainException.validation("target", "Owner does not support NEW_FORKED_SESSION");
        }
        requireOwnedSession(ownership, owner, sourceSessionId(forkedSession.sourceThreadId()));
      }
      case AcceptCommandsTarget.Thread ignoredThread -> throw new IllegalStateException(
          "owner-aware authorization does not support THREAD targets");
    }
  }

  /** 由来源执行根 Thread 解析其 Session；Thread 不可解析说明来源已不一致，失败而不是回退。 */
  private UUID sourceSessionId(UUID sourceThreadId) {
    return requireRuntime()
        .findThreadSessionId(sourceThreadId)
        .orElseThrow(() -> DomainException.notFound("thread", "fork source thread does not exist"));
  }

  /** Session 已存在时，NEW_SESSION 只能作为同 owner 的精确 replay，内部无归属 Session 也不得被产品 owner 接管。 */
  private boolean sessionExists(UUID sessionId) {
    return requireRuntime().sessionExists(sessionId);
  }

  private void requireOwnedSession(SessionOwnership ownership, SessionOwner owner, UUID sessionId) {
    if (!ownership.ownsSession(owner, sessionId)) {
      throw DomainException.validation("session", "Session ownership is inconsistent");
    }
  }

  /** 全新 Session 的 preflight 调用产品归属钩子并物化 USER_MESSAGE 附件；精确 replay 不重复执行。 */
  private AcceptancePreflight preflight(
      SessionOwnership ownership, SessionOwner owner, AcceptCommandsTarget target) {
    return (session, commands, copiedEntries) -> {
      if (target instanceof AcceptCommandsTarget.NewRootSession
          || target instanceof AcceptCommandsTarget.NewForkedSession) {
        ownership.sessionCreated(owner, session.id());
      }
      if (target instanceof AcceptCommandsTarget.NewForkedSession) {
        retainCopiedResources(copiedEntries, session.id());
      }
      return prepareUserContents(session.id(), commands);
    };
  }

  /**
   * 会话 fork 复制有效上下文时必须把被复制消息引用的 blob 重新 retain 到新 Session：新身份的 Entry 只携带 blobId，blob 生命周期由 {@code
   * session_blob_ref} 持有，源 Session 删除后新历史仍须可用。已存在的 ref 由幂等 retain 自然去重。
   */
  private void retainCopiedResources(List<Entry> copiedEntries, UUID sessionId) {
    for (Entry entry : copiedEntries) {
      for (ResourceMessageContent resource : referencedResources(entry.payload())) {
        refManager.retainRef(sessionId, resource.blobId());
      }
    }
  }

  /** 提取 Entry payload 中模型可见消息引用的 Resource（含 tool result 内嵌内容）；其它 payload 无资源引用。 */
  private static List<ResourceMessageContent> referencedResources(EntryPayload payload) {
    List<AgentMessageContent> contents =
        switch (payload) {
          case MessagePayload message -> message.message().contents();
          case CustomMessagePayload message -> message.message().contents();
          case NotificationPayload message -> message.message().contents();
          case AssistantAbortedPayload message -> message.message().contents();
          default -> List.of();
        };
    List<ResourceMessageContent> resources = new ArrayList<>();
    collectResources(contents, resources);
    return resources;
  }

  private static void collectResources(
      List<AgentMessageContent> contents, List<ResourceMessageContent> resources) {
    for (AgentMessageContent content : contents) {
      if (content instanceof ResourceMessageContent resource) {
        resources.add(resource);
      } else if (content instanceof ToolResultMessageContent result) {
        collectResources(result.contents(), resources);
      }
    }
  }

  /**
   * 准备 USER_MESSAGE 内容：瞬时 ATTACHMENT(uploadId) 物化为 durable RESOURCE，已有 RESOURCE 校验 Session
   * ownership；保持 idempotencyKey/requestHash 不变。转换核心与只读请求预览共用 {@link UserMessageContentPreparer}，两者仅
   * READY upload 的取得方式不同。
   */
  private List<NewThreadCommand> prepareUserContents(
      UUID sessionId, List<NewThreadCommand> commands) {
    return contentPreparer.prepare(sessionId, commands);
  }

  /**
   * 消费一个 READY upload：锁定上传行（PENDING / 过期 / 不存在确定性拒绝）、以上传行权威文件名与权威媒体类型构造 RESOURCE、写 session blob
   * ref（retain 先于 release）、标记已消费上传 cleanup（release 其引用）。全部在同一外事务内，失败整体回滚；S3 清理由提交后的 Storage
   * Maintenance 完成。
   */
  private UserMessageContentPreparer.ReadyAttachment consumeAttachment(
      UUID sessionId, UUID uploadId) {
    StorageUploadService.ReadyUpload ready;
    try {
      ready = uploadService.lockReady(uploadId);
    } catch (DomainException error) {
      if (error.code() != DomainErrorCode.NOT_FOUND || !"upload".equals(error.resource())) {
        throw error;
      }
      throw DomainException.validation("attachment", "Attachment upload cannot be consumed");
    }
    refManager.retainRef(sessionId, ready.blobId());
    uploadService.delete(uploadId);
    return new UserMessageContentPreparer.ReadyAttachment(ready.blobId(), ready.filename());
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }
}
