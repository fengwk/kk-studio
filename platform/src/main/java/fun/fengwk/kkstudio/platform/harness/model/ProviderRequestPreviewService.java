package fun.fengwk.kkstudio.platform.harness.model;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
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
import fun.fengwk.kkstudio.platform.orchestration.UserMessageContentPreparer;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.error.StorageVerificationException;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 发送前 Provider 协议请求体预览：在只读事实上用正式发送路径的规划、物化、Provider 解析与协议编码生成最终请求体，不写任何 durable 状态、不消费 upload、不触发
 * transport。
 *
 * <p>三个入口只在「事实边界」上不同：既有 Thread 的下一轮草稿用快照 cursor 与空闲/queued/压缩判定；本地分支草稿只有 Session 与分支起点
 * Entry，草稿尚未落库为 Thread，因此 candidate Thread/Entry id 都只是本次内存 UUID；历史模型输出以 ROOT 到其 parent 为请求前缀，并把该
 * 输出的记录时间显式交给规划，绝不用当前时间冒充。
 *
 * <p>预览与正式发送同源：candidate 历史由同一 {@link TurnPlanPreview} 构造，冻结请求来自同一 {@link
 * DatabaseTurnResolver#planLive(UUID, EntryPath, Instant)}（历史模型输出走 {@link
 * DatabaseTurnResolver#planHistorical(UUID, EntryPath, Instant)}），有效请求来自同一 {@link
 * DatabaseProviderResolutionService#resolve}，最终字节来自同一 {@link
 * ProviderAdapter#encodeRequestBody}。附件只做 READY 的只读 peek，RESOURCE 仍必须由目标 Session 持有。压缩 Turn
 * 也是真实模型输出：按其 durable COMPACTION TURN_START 冻结的 executionModel 与预算走同一条物化/编码路径。
 */
public final class ProviderRequestPreviewService {

  /** 尚未落库的分支草稿没有 Thread cursor：它的第一批命令就是该新 Thread 的 sequence 起点。 */
  private static final long FIRST_COMMAND_SEQUENCE = 1L;

  private final HarnessRuntime runtime;
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
      DatabaseTurnResolver turnResolver,
      DatabaseProviderResolutionService providerResolution,
      CompactionConfigProvider compactionConfigProvider,
      SessionBlobRefManager refManager,
      StorageBlobManager blobManager,
      StorageUploadService uploadService,
      Clock clock) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
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
   * @param threadId path 中的目标 Thread，必须与 batch 的 threadId 完全一致
   * @param command 与发送完全相同的通用命令批（target 必须是 THREAD）
   * @return 最终请求体、UTF-8 字节数、providerType/modelName、source head cursor 与固定 notice
   * @throws ProviderRequestPreviewUnavailableException 当前事实不允许精确预览（快照漂移、非空闲、queued、压缩、附件未 READY、
   *     adapter 不支持预览或编码失败）
   */
  public HarnessProviderRequestPreviewDTO preview(UUID threadId, AcceptCommandsCommand command) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(command, "command");
    AcceptCommandsTarget.Thread target = requireThreadTarget(threadId, command.target());
    requirePreviewCommandShape(command.commands());

    ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
    requirePreviewableSnapshot(snapshot, target);

    // 顺序契约：所有廉价快照闸门（cursor / queued / 空闲 / 下一步压缩）先于内容换算，因此未 READY 的表单或会被压缩的
    // Thread 都在任何 upload 行锁、blob 读取与 Session ref 查询之前失败。
    List<NewThreadCommand> prepared =
        contentPreparer.prepare(snapshot.thread().sessionId(), command.commands());
    Instant now = clock.instant();
    EntryPath candidatePath =
        TurnPlanPreview.inputCandidatePath(
            threadId, snapshot.entryPath(), snapshot.thread().nextCommandSequence(), prepared, now);
    return planAndEncode(
        turnResolver.planLive(threadId, candidatePath, now),
        candidatePath,
        now,
        snapshot.entryPath().head().id(),
        HarnessProviderRequestPreviewDTO.DRAFT_REQUEST_PREVIEW,
        HarnessProviderRequestPreviewDTO.DRAFT_NOTICE);
  }

  /**
   * 现算一次本地分支草稿的 Provider 协议请求体预览：草稿尚未落库为 Thread，因此边界只有 Session 与分支起点 Entry （ROOT 或已关闭的 TURN_END，与
   * NEW_THREAD 的合法落点一致）。
   *
   * <p>不做任何 Thread cursor / queued / 空闲 / 自动压缩判定（那些事实属于既有 Thread），也绝不为了预览先创建 Thread。
   *
   * @param sessionId path 中的目标 Session；不存在时由 {@link HarnessRuntime} 以 typed 异常拒绝
   * @param startEntryId 草稿分支起点，必须属于该 Session
   * @param commands 与发送完全相同的通用命令批（SET_* 前缀 + 末尾 USER_MESSAGE）
   */
  public HarnessProviderRequestPreviewDTO previewDraft(
      UUID sessionId, UUID startEntryId, List<NewThreadCommand> commands) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(startEntryId, "startEntryId");
    Objects.requireNonNull(commands, "commands");
    requirePreviewCommandShape(commands);

    Map<UUID, Entry> entries = sessionEntries(sessionId);
    Entry source = requireSessionEntry(entries, startEntryId, "start entry");
    requireDraftStartEntry(source);
    EntryPath sourcePath = entryPath(entries, source.id());
    List<NewThreadCommand> prepared = contentPreparer.prepare(sessionId, commands);

    Instant now = clock.instant();
    UUID candidateThreadId = UUID.randomUUID();
    EntryPath candidatePath =
        TurnPlanPreview.inputCandidatePath(
            candidateThreadId, sourcePath, FIRST_COMMAND_SEQUENCE, prepared, now);
    return planAndEncode(
        turnResolver.planLive(candidateThreadId, candidatePath, now),
        candidatePath,
        now,
        source.id(),
        HarnessProviderRequestPreviewDTO.DRAFT_REQUEST_PREVIEW,
        HarnessProviderRequestPreviewDTO.DRAFT_NOTICE);
  }

  /**
   * 现算一次历史模型输出的 Provider 协议请求体预览：请求前缀严格是 ROOT 到该输出的 parent，规划时间显式取该输出的记录时间。
   *
   * <p>catalog 与 Provider 配置都已不是当时的那一份，因此这里按当前定义重建；响应以 {@link
   * HarnessProviderRequestPreviewDTO#HISTORICAL_NOTICE} 明确声明它不等于原始发送字节。父 COMPACTION turn 自身不创建
   * ModelInvocation，其 provider 请求由独立的压缩子 Thread 发出，因此父侧无法重建该请求：这里明确 typed 拒绝并指向子的真实调用，绝不伪造一份零工具请求。
   *
   * @param sessionId path 中的目标 Session；不存在时由 {@link HarnessRuntime} 以 typed 异常拒绝
   * @param entryId 被查看的模型输出 Entry，必须属于该 Session 且携带 assistantMetadata
   */
  public HarnessProviderRequestPreviewDTO previewHistorical(UUID sessionId, UUID entryId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(entryId, "entryId");

    Map<UUID, Entry> entries = sessionEntries(sessionId);
    Entry output = requireSessionEntry(entries, entryId, "entry");
    if (output.payload() instanceof CompactionPayload) {
      // 压缩结果由父 COMPACTION turn 提交，但请求属于压缩子 Thread（其 ROOT/输入回合在另一个 Session 里）；父侧没有可重建的请求。
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_UNSUPPORTED,
          "a COMPACTION parent turn has no model invocation; its provider request belongs to the"
              + " compaction child thread");
    }
    UUID requestHeadEntryId = requireRequestHead(output);
    EntryPath requestPath = entryPath(entries, requestHeadEntryId);
    Instant recordedAt = output.createdAt();
    // 历史路径可能不属于任何一个现存 Thread；规划本身不回查 Thread 归属，因此这里同样只给 candidate id。
    UUID candidateThreadId = UUID.randomUUID();
    return planAndEncode(
        turnResolver.planHistorical(candidateThreadId, requestPath, recordedAt),
        requestPath,
        recordedAt,
        requestHeadEntryId,
        HarnessProviderRequestPreviewDTO.HISTORICAL_REQUEST_PREVIEW,
        HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE);
  }

  /** 三种预览共用的只读尾部：冻结规划 -> 物化 -> 正式协议编码 -> 稳定响应投影。 */
  private HarnessProviderRequestPreviewDTO planAndEncode(
      LiveTurnPlan plan,
      EntryPath candidatePath,
      Instant plannedAt,
      UUID sourceHeadEntryId,
      String kind,
      String notice) {
    if (plan instanceof LiveTurnPlan.Rejected) {
      // 规划的错误码与自由文本详情都不属于公开预览协议。
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_PLANNING_FAILED, "request cannot be planned for preview");
    }
    ModelRequestSpec spec = ((LiveTurnPlan.Planned) plan).spec();
    ProviderRequest request = materializer.materialize(candidatePath, spec);
    byte[] body = encodeRequestBody(spec.providerType(), spec, request);

    HarnessProviderRequestPreviewDTO dto = new HarnessProviderRequestPreviewDTO();
    dto.setKind(kind);
    dto.setGeneratedAt(plannedAt);
    dto.setProviderType(spec.providerType().name());
    dto.setModelName(spec.model().modelName());
    dto.setBodyByteSize(body.length);
    dto.setBodyJson(new String(body, StandardCharsets.UTF_8));
    dto.setSourceHeadEntryId(sourceHeadEntryId.toString());
    dto.setNotice(notice);
    return dto;
  }

  /** Session 的完整 Entry tree（含非 head 分支）：id 索引同时充当祖先解析的事实来源。 */
  private Map<UUID, Entry> sessionEntries(UUID sessionId) {
    List<Entry> entries = runtime.getSessionEntries(sessionId);
    Map<UUID, Entry> entriesById = new LinkedHashMap<>(entries.size());
    for (Entry entry : entries) {
      entriesById.put(entry.id(), entry);
    }
    return entriesById;
  }

  /** 预览的 Entry 边界必须属于 path 上的 Session；跨 Session 引用是客户端错误（400），绝不回退到其它 Session 的 Entry。 */
  private static Entry requireSessionEntry(Map<UUID, Entry> entries, UUID entryId, String name) {
    Entry entry = entries.get(entryId);
    if (entry == null) {
      throw new IllegalArgumentException(name + " does not belong to the session: " + entryId);
    }
    return entry;
  }

  /**
   * 草稿分支起点必须与 NEW_THREAD 的合法落点一致：ROOT 或已关闭的 TURN_END。
   *
   * <p>消息内部、工具结果或仍在进行中的回合都无法承载新 Turn，预览必须与正式接受一样在触碰任何规划/编码之前确定性拒绝；起点自身就是 durable
   * 记录的一部分，因此不需要（也不允许）调用方伪造 owner 或先创建 Thread。
   */
  private static void requireDraftStartEntry(Entry entry) {
    EntryType type = entry.payload().type();
    if (type != EntryType.ROOT && type != EntryType.TURN_END) {
      throw new IllegalArgumentException(
          "draft preview requires a ROOT or TURN_END start entry but got " + type);
    }
  }

  /**
   * 历史预览只接受模型输出本身：携带 assistantMetadata 的 ASSISTANT MESSAGE。请求前缀就是它的 parent，因此本次输出与未来历史绝不进入 请求体；父
   * COMPACTION 结果已在 {@link #previewHistorical} 前置拒绝，不会走到这里。
   */
  private static UUID requireRequestHead(Entry output) {
    if (!carriesModelOutputMetadata(output)) {
      throw new IllegalArgumentException(
          "provider request preview requires an assistant model output entry");
    }
    if (output.parentEntryId() == null) {
      throw new IllegalStateException(
          "assistant model output must have a request head entry: " + output.id());
    }
    return output.parentEntryId();
  }

  /** 只有真正发生过 provider 调用的助手输出才带 assistantMetadata，才存在可重建的请求前缀。 */
  private static boolean carriesModelOutputMetadata(Entry output) {
    return output.payload() instanceof MessagePayload message
        && message.assistantMetadata() != null;
  }

  /**
   * 从 Session Entry tree 复原 root-to-head 路径；parent 缺失说明 durable 树已损坏，直接失败而不是给出错误前缀。路径合法性（单一
   * Session、ROOT 在前、parent 链连续、Turn grammar）由 {@link EntryPath} 权威校验，因此无法承载新 Turn 的分支起点会与正式接受
   * 一样被确定性拒绝。
   */
  private static EntryPath entryPath(Map<UUID, Entry> entries, UUID headEntryId) {
    LinkedList<Entry> chain = new LinkedList<>();
    UUID cursor = headEntryId;
    while (cursor != null) {
      Entry entry = entries.get(cursor);
      if (entry == null) {
        throw new IllegalStateException("session entry parent is missing: " + cursor);
      }
      chain.addFirst(entry);
      cursor = entry.parentEntryId();
    }
    return new EntryPath(chain);
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
