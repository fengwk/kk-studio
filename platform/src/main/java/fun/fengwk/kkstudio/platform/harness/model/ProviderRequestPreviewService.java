package fun.fengwk.kkstudio.platform.harness.model;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanPreview;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewUnavailableException.Reason;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.UserMessageContentPreparer;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 发送前 Provider 协议请求体预览：在只读快照上用正式发送路径的规划、物化、Provider 解析与协议编码生成最终请求体，不写任何 durable 状态、不消费 upload、不触发
 * transport。
 *
 * <p>预览与正式发送严格同源：candidate 历史由同一纯 {@link TurnPlanPreview}（内部复用 ThreadProcessor 的同一 {@code
 * TurnPlanBuilder}）构造，冻结请求来自同一 {@link DatabaseTurnResolver#planLive}，请求投影来自同一 {@link
 * ModelRequestMaterializer}，有效请求来自同一 {@link DatabaseProviderResolutionService#resolve}（含同一 Resource
 * 物化、cache control 规范化与 connection generation 校验），最终字节来自同一 {@link
 * ProviderAdapter#encodeRequestBody}。 因此只要前置事实一致，预览体与实际发送体逐字节一致。
 *
 * <p>预览 fail-closed：草稿只允许「SET_* 设置前缀 + 恰好一条末尾 USER_MESSAGE」（GOAL / CUSTOM_MESSAGE 是各自的专属功能，
 * 不属于本预览），owner 只允许 CHAT 且必须复用 {@link HarnessCommandAcceptanceOrchestrator} 的只读归属校验， Thread 必须空闲、无
 * queued 命令且 cursor（head + next command sequence）一致，下一步必定是自动压缩时明确拒绝。附件只做 READY 的只读 peek（不
 * retain、不删除、不增 Session ref），RESOURCE 仍必须由目标 Session 持有。
 *
 * <p>预览是点击时快照：它与随后真正发送之间没有任何 CAS，因此绝不声称发送结果与预览相同；{@link
 * HarnessProviderRequestPreviewDTO#snapshotNotice} 是响应契约的一部分。所有拒绝消息都是稳定且安全的文本，不回显 credential、
 * Authorization header、URL、上传/对象存储内部标识或规划自由文本详情。
 */
public final class ProviderRequestPreviewService {

  private final HarnessRuntime runtime;
  private final HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator;
  private final DatabaseTurnResolver turnResolver;
  private final DatabaseProviderResolutionService providerResolution;
  private final CompactionConfigProvider compactionConfigProvider;
  private final StorageUploadService uploadService;
  private final Clock clock;
  private final ModelRequestMaterializer materializer = new ModelRequestMaterializer();
  private final AutomaticCompactionPlanner compactionPlanner = new AutomaticCompactionPlanner();
  private final ThreadContextClassifier contextClassifier = new ThreadContextClassifier();
  private final UserMessageContentPreparer contentPreparer;

  public ProviderRequestPreviewService(
      HarnessRuntime runtime,
      HarnessCommandAcceptanceOrchestrator acceptanceOrchestrator,
      DatabaseTurnResolver turnResolver,
      DatabaseProviderResolutionService providerResolution,
      CompactionConfigProvider compactionConfigProvider,
      SessionBlobRefManager refManager,
      StorageBlobManager blobManager,
      StorageUploadService uploadService,
      Clock clock) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.acceptanceOrchestrator =
        Objects.requireNonNull(acceptanceOrchestrator, "acceptanceOrchestrator");
    this.turnResolver = Objects.requireNonNull(turnResolver, "turnResolver");
    this.providerResolution = Objects.requireNonNull(providerResolution, "providerResolution");
    this.compactionConfigProvider =
        Objects.requireNonNull(compactionConfigProvider, "compactionConfigProvider");
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.clock = Objects.requireNonNull(clock, "clock");
    // 预览侧只做 READY 的只读 peek：绝不 retain、绝不 delete、绝不写 Session ref。
    this.contentPreparer =
        new UserMessageContentPreparer(
            Objects.requireNonNull(refManager, "refManager"),
            Objects.requireNonNull(blobManager, "blobManager"),
            this::peekAttachment);
  }

  /**
   * 现算一次草稿的 Provider 协议请求体预览。
   *
   * @param threadId path 中的目标 Thread，必须与 batch target 的 threadId 完全一致
   * @param owner 产品 owner：只接受 CHAT
   * @param command 与发送完全相同的 owner-aware 命令批（target 必须是 THREAD）
   * @return 最终请求体、UTF-8 字节数、providerType/modelName、source head cursor 与快照提示
   * @throws ProviderRequestPreviewUnavailableException 当前事实不允许精确预览（快照漂移、非空闲、queued、压缩、附件未 READY、
   *     adapter 不支持预览或编码失败）
   */
  public HarnessProviderRequestPreviewDTO preview(
      UUID threadId, OwnerRef owner, AcceptCommandsCommand command) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(command, "command");
    AcceptCommandsTarget.Thread target = requireThreadTarget(threadId, command.target());
    requireProductOwner(owner);
    requirePreviewCommandShape(command.commands());

    // 只读归属校验：与正式接受共用同一判定，预览不获得任何 owner 权限。
    acceptanceOrchestrator.authorizeThread(owner, threadId);

    ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
    requirePreviewableSnapshot(snapshot, target);

    // 顺序契约：所有廉价快照闸门（cursor / queued / 空闲 / 下一步压缩）先于内容换算，因此未 READY 的表单或会被压缩的
    // Thread 都在任何 upload 行锁、blob 读取与 Session ref 查询之前失败。
    List<NewThreadCommand> prepared =
        contentPreparer.prepare(snapshot.thread().sessionId(), command.commands());
    EntryPath candidatePath =
        TurnPlanPreview.inputCandidatePath(
            threadId,
            snapshot.entryPath(),
            snapshot.thread().nextCommandSequence(),
            prepared,
            clock.instant());

    LiveTurnPlan plan = turnResolver.planLive(threadId, candidatePath);
    if (plan instanceof LiveTurnPlan.Rejected) {
      // 规划的错误码与自由文本详情都不属于公开预览协议。
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_PLANNING_FAILED, "request cannot be planned for preview");
    }
    ModelRequestSpec spec = ((LiveTurnPlan.Planned) plan).spec();
    ProviderRequest request = materializer.materialize(candidatePath, spec);
    byte[] body = encodeRequestBody(spec.providerType(), spec, request);

    HarnessProviderRequestPreviewDTO dto = new HarnessProviderRequestPreviewDTO();
    dto.setKind(HarnessProviderRequestPreviewDTO.KIND);
    dto.setGeneratedAt(clock.instant());
    dto.setProviderType(spec.providerType().name());
    dto.setModelName(spec.model().modelName());
    dto.setBodyByteSize(body.length);
    dto.setBodyJson(new String(body, StandardCharsets.UTF_8));
    dto.setSourceHeadEntryId(snapshot.entryPath().head().id().toString());
    dto.setSnapshotNotice(HarnessProviderRequestPreviewDTO.SNAPSHOT_NOTICE);
    return dto;
  }

  /** preview 只覆盖既有 Thread：target 必须是 THREAD 且 threadId 与 path 完全一致。 */
  private static AcceptCommandsTarget.Thread requireThreadTarget(
      UUID threadId, AcceptCommandsTarget target) {
    if (!(target instanceof AcceptCommandsTarget.Thread thread)) {
      throw new IllegalArgumentException("provider request preview requires a THREAD target");
    }
    if (!thread.threadId().equals(threadId)) {
      throw new IllegalArgumentException("target threadId does not match the request path");
    }
    return thread;
  }

  /** 预览只服务 Chat 草稿：Issue Agent Session 的命令由 Issue 业务工作流拥有。 */
  private static void requireProductOwner(OwnerRef owner) {
    if (!(owner instanceof OwnerRef.Chat)) {
      throw new IllegalArgumentException("provider request preview is limited to CHAT owners");
    }
  }

  /**
   * 预览命令形状：可执行的前置 SET_* 设置（固定顺序、各自至多一次）加上恰好一条末尾 USER_MESSAGE。GOAL 与 CUSTOM_MESSAGE
   * 是各自的专属功能，不属于草稿消息预览，因此一律拒绝。
   */
  private static void requirePreviewCommandShape(List<NewThreadCommand> commands) {
    if (commands.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }
    for (int i = 0; i < commands.size(); i++) {
      ThreadCommandType type = commands.get(i).payload().type();
      if (type == ThreadCommandType.USER_MESSAGE) {
        if (i != commands.size() - 1) {
          throw new IllegalArgumentException("the preview user message must be the final command");
        }
        return;
      }
      if (!type.isSetting()) {
        throw new IllegalArgumentException(
            "provider request preview only accepts SET_* settings before the user message: "
                + type);
      }
    }
    throw new IllegalArgumentException(
        "provider request preview requires exactly one trailing USER_MESSAGE");
  }

  /** 快照必须空闲、无 queued 命令且 cursor 与 batch 期望完全一致，否则 fail closed。 */
  private void requirePreviewableSnapshot(
      ThreadSnapshot snapshot, AcceptCommandsTarget.Thread target) {
    ThreadState thread = snapshot.thread();
    if (!thread.headEntryId().equals(target.expectedHeadEntryId())
        || thread.nextCommandSequence() != target.expectedNextCommandSequence()) {
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_STALE_CURSOR,
          "thread head/next command sequence does not match the preview expectation");
    }
    if (!snapshot.queuedCommands().isEmpty()) {
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_QUEUED_COMMANDS,
          "thread still has queued commands; the preview would not reflect them");
    }
    ThreadContext context =
        contextClassifier.classify(
            thread, snapshot.entryPath(), snapshot.model(), snapshot.toolSiblings());
    if (!(context instanceof ThreadContext.IdleOrHistorical)) {
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_THREAD_BUSY, "thread is not idle; the next step is not an input turn");
    }
    if (compactionPlanner.plan(
            thread, snapshot.entryPath(), compactionConfigProvider.compactionConfig(), true)
        != null) {
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_COMPACTION_REQUIRED,
          "the next step for this thread is automatic compaction; the request cannot be previewed "
              + "precisely");
    }
  }

  /**
   * 附件只读 peek：取得权威 blobId 与文件名，但绝不 retain、delete 或标记 cleanup。未 READY 的上传（PENDING / 过期 / 已请求 cleanup
   * / 已被 claim）与缺失上传一律确定性拒绝，预览绝不等同于消费授权。
   */
  private UserMessageContentPreparer.ReadyAttachment peekAttachment(UUID sessionId, UUID uploadId) {
    StorageUploadService.ReadyUpload ready;
    try {
      ready = uploadService.peekReady(uploadId);
    } catch (StorageResourceNotFoundException | StorageVerificationException error) {
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_ATTACHMENT_NOT_READY, "attachment upload is not READY for preview");
    }
    return new UserMessageContentPreparer.ReadyAttachment(ready.blobId(), ready.filename());
  }

  /** Provider 解析与编码：generation/type 漂移、adapter 缺失一律确定性拒绝；不支持预览的 adapter 绝不伪装成空体。 */
  private byte[] encodeRequestBody(
      ProviderType providerType, ModelRequestSpec spec, ProviderRequest request) {
    ProviderResolutionService.ResolvedExecution resolved;
    try {
      resolved =
          providerResolution.resolve(providerType, spec.providerConnectionGenerationId(), request);
    } catch (IllegalArgumentException | IllegalStateException error) {
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_PROVIDER_UNAVAILABLE, "cannot resolve the current provider for preview");
    }
    try {
      return resolved.encodeRequestBody();
    } catch (UnsupportedOperationException error) {
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_UNSUPPORTED,
          providerType + " adapter does not support request body preview");
    } catch (ProviderException error) {
      // 编码失败的原因文本可能携带请求细节，这里只保留稳定的失败类别。
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_ENCODING_FAILED, "request body cannot be encoded for preview", error);
    }
  }
}
