package fun.fengwk.kkstudio.platform.orchestration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.AcceptedCommands;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Harness 命令接受与用户消息内容物化的事务边界。
 *
 * <p>创建与既有 Thread 写入分别由调用方选择入口；Runtime 接受和附件物化在同一物理事务内完成。
 */
@Service
public class HarnessCommandAcceptanceOrchestrator {

  private final ObjectProvider<HarnessRuntime> runtimes;
  private final StorageUploadService uploadService;
  private final SessionBlobRefManager refManager;
  private final ModelImageCountLimitResolver modelImageCountLimitResolver;
  private final UserMessageContentPreparer contentPreparer;

  public HarnessCommandAcceptanceOrchestrator(
      ObjectProvider<HarnessRuntime> runtimes,
      StorageUploadService uploadService,
      SessionBlobRefManager refManager,
      StorageBlobManager blobManager,
      SystemSettingsSnapshot settingsSnapshot,
      ModelImageCountLimitResolver modelImageCountLimitResolver) {
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.refManager = Objects.requireNonNull(refManager, "refManager");
    this.modelImageCountLimitResolver =
        Objects.requireNonNull(modelImageCountLimitResolver, "modelImageCountLimitResolver");
    this.contentPreparer =
        new UserMessageContentPreparer(
            this.refManager,
            Objects.requireNonNull(blobManager, "blobManager"),
            this::consumeAttachment,
            () -> settingsSnapshot.get().storageMedia().imageTier(),
            () -> settingsSnapshot.get().storageMedia().maxImagesPerRequest());
  }

  /** The caller locks the workspace and records the new placement in this physical transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public AcceptedCommands createSession(AcceptCommandsCommand command) {
    if (!(command.target() instanceof AcceptCommandsTarget.NewRootSession)) {
      throw new IllegalArgumentException("session creation requires a new root target");
    }
    return requireRuntime()
        .acceptCommands(
            command,
            (session, commands, copiedEntries, branchSettings) ->
                prepareUserContents(session.id(), commands, branchSettings));
  }

  /**
   * 接受既有 Thread 的通用命令批：target 由调用方从 path 与精确 cursor 组合。
   *
   * <p>Runtime 从 Thread 解析 Session，preflight 在同一事务内完成 USER_MESSAGE 附件物化与 RESOURCE 的 Session
   * 校验；本服务不改写目标 Thread 的所属事实。
   */
  @Transactional
  public AcceptedCommands acceptOnThread(AcceptCommandsCommand command) {
    Objects.requireNonNull(command, "command");
    if (!(command.target() instanceof AcceptCommandsTarget.Thread)) {
      throw new IllegalArgumentException("thread command acceptance requires a THREAD target");
    }
    HarnessRuntime runtime = requireRuntime();
    AcceptancePreflight preflight =
        (session, commands, copiedEntries, branchSettings) ->
            prepareUserContents(session.id(), commands, branchSettings);
    return runtime.acceptCommands(command, preflight);
  }

  /**
   * 准备 USER_MESSAGE 内容：瞬时 ATTACHMENT(uploadId) 物化为 durable RESOURCE，已有 RESOURCE 校验 Session
   * ownership；保持 idempotencyKey/requestHash 不变。转换核心与只读请求预览共用 {@link UserMessageContentPreparer}，两者仅
   * READY upload 的取得方式不同。
   */
  private List<NewThreadCommand> prepareUserContents(
      UUID sessionId, List<NewThreadCommand> commands, BranchSettings branchSettings) {
    if (commands.stream()
        .noneMatch(command -> command.payload() instanceof UserMessageCommandPayload)) {
      return contentPreparer.prepare(sessionId, commands);
    }
    for (NewThreadCommand command : commands) {
      if (command.payload() instanceof SetExecutionCommandPayload setExecution) {
        branchSettings = branchSettings.withExecution(setExecution.execution());
      }
    }
    Integer protocolMaxImages = modelImageCountLimitResolver.resolve(branchSettings);
    return contentPreparer.prepare(sessionId, commands, protocolMaxImages);
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
    return new UserMessageContentPreparer.ReadyAttachment(
        ready.blobId(),
        ready.filename(),
        ready.originalWidth(),
        ready.originalHeight(),
        ready.storedWidth(),
        ready.storedHeight());
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }
}
