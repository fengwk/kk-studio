package fun.fengwk.kkstudio.platform.harness.model;

import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.processor.TurnPlanPreview;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLiveState;
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
import java.util.ArrayList;
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
            threadId,
            snapshot.requireFullPath(),
            snapshot.thread().nextCommandSequence(),
            prepared,
            now);
    return planAndEncode(
        turnResolver.planLive(threadId, candidatePath, now),
        candidatePath,
        now,
        snapshot.entryState().head().id(),
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
   * HarnessProviderRequestPreviewDTO#HISTORICAL_NOTICE} 明确声明它不等于原始发送字节。
   *
   * <p>父 COMPACTION 结果自身不创建 ModelInvocation：它的摘要请求由独立的压缩子 Thread 发出。此时沿父 COMPACTION TURN_START 冻结的
   * child Thread / join 票据定位子执行，并以 join 冻结的 finalAnswerEntryId（而不是 child 当前
   * head）找到真正产出该摘要的最后一次调用前缀；工具 loop、输出预算与 cache 约束都按该子执行的冻结事实重建。关联缺失或不属于同一冻结执行时 typed 拒绝，绝不猜测。
   *
   * @param sessionId path 中的目标 Session；不存在时由 {@link HarnessRuntime} 以 typed 异常拒绝
   * @param entryId 被查看的模型输出 Entry，必须属于该 Session 且携带 assistantMetadata（压缩结果为 {@link
   *     CompactionPayload}）
   */
  public HarnessProviderRequestPreviewDTO previewHistorical(UUID sessionId, UUID entryId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(entryId, "entryId");

    Map<UUID, Entry> entries = sessionEntries(sessionId);
    Entry output = requireSessionEntry(entries, entryId, "entry");
    if (output.payload() instanceof CompactionPayload) {
      return previewCompactionChild(sessionId, entries, output);
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

  /**
   * 父 COMPACTION 结果 -> 冻结压缩子执行的真实最后一次调用。
   *
   * <p>父 TURN_START 冻结 child Thread 与 join 票据；join 的 finalAnswerEntryId 是这次压缩执行的最终答复边界。这里只沿
   * finalAnswer 的 parent（该次调用的 request head）构造前缀，绝不使用 child 当前 head，因此同一条 child Thread 上后续续跑
   * 不会污染旧压缩结果。child Thread 的不可变执行约束在规划后再次收紧输出预算并强制关闭 prompt cache，与正式提交路径一致。
   */
  private HarnessProviderRequestPreviewDTO previewCompactionChild(
      UUID parentSessionId, Map<UUID, Entry> parentEntries, Entry output) {
    EntryPath outputPath = entryPath(parentEntries, output.id());
    Entry turnEnd = requireCompactionTurnEnd(parentEntries, output);
    List<Entry> closedEntries = new ArrayList<>(outputPath.entries());
    closedEntries.add(turnEnd);
    EntryPath closedPath;
    try {
      closedPath = new EntryPath(closedEntries);
    } catch (IllegalArgumentException error) {
      throw frozenCompactionUnavailable(
          "compaction turn end does not belong to the result path", error);
    }
    CompactionTurns.CompactionTurn turn;
    try {
      turn = CompactionTurns.ofResult(closedPath, output.id());
    } catch (IllegalStateException error) {
      throw frozenCompactionUnavailable(
          "compaction result has no enclosing COMPACTION turn", error);
    }
    CompactionStart frozen = turn.freezing();
    UUID ownerThreadId = turn.start().ownerThreadId();
    if (frozen.childThreadId() == null || frozen.joinInvocationId() == null) {
      // pending 压缩 TURN_START 尚未创建子执行，没有可预览的真实调用。
      throw frozenCompactionUnavailable("compaction child execution is not frozen");
    }

    ThreadJoin join =
        runtime
            .findJoin(frozen.joinInvocationId())
            .orElseThrow(() -> frozenCompactionUnavailable("compaction join does not exist"));
    requireFrozenCompactionJoin(join, frozen, ownerThreadId);

    ThreadSnapshot childSnapshot;
    try {
      childSnapshot = runtime.getThreadSnapshot(frozen.childThreadId());
    } catch (HarnessRuntimeNotFoundException error) {
      throw frozenCompactionUnavailable("compaction child thread does not exist", error);
    }
    ThreadState childThread = childSnapshot.thread();
    ThreadConstraints constraints =
        requireFrozenChildThread(childThread, parentSessionId, ownerThreadId, frozen);

    Map<UUID, Entry> childEntries = sessionEntries(childThread.sessionId());
    Entry finalAnswer = childEntries.get(join.finalAnswerEntryId());
    MessagePayload finalMessage =
        finalAnswer != null && finalAnswer.payload() instanceof MessagePayload message
            ? message
            : null;
    if (finalMessage == null
        || finalMessage.message().role() != AgentMessageRole.ASSISTANT
        || finalMessage.assistantMetadata() == null) {
      throw frozenCompactionUnavailable(
          "compaction join final answer is not an assistant model output in the child session");
    }
    CompactionPayload compaction = (CompactionPayload) output.payload();
    if (compaction.assistantMetadata() == null
        || !compaction.assistantMetadata().equals(finalMessage.assistantMetadata())) {
      throw frozenCompactionUnavailable(
          "compaction result metadata does not belong to the frozen final answer");
    }
    requireWithinFrozenExecution(childEntries, join, finalAnswer);

    UUID requestHeadEntryId = requireRequestHead(finalAnswer);
    EntryPath requestPath;
    try {
      requestPath = entryPath(childEntries, requestHeadEntryId);
    } catch (IllegalStateException error) {
      throw frozenCompactionUnavailable(
          "compaction final answer has a broken request prefix", error);
    }
    // 子响应只接受携带 assistantMetadata 的真实模型输出；其请求前缀就是这个输出的 parent。
    Instant recordedAt = finalAnswer.createdAt();
    LiveTurnPlan plan =
        turnResolver.planHistorical(frozen.childThreadId(), requestPath, recordedAt);
    if (plan instanceof LiveTurnPlan.Planned planned) {
      plan =
          new LiveTurnPlan.Planned(
              applyConstraints(planned.spec(), constraints),
              planned.contextWindow(),
              planned.candidateTools(),
              planned.skills());
    }
    return planAndEncode(
        plan,
        requestPath,
        recordedAt,
        requestHeadEntryId,
        HarnessProviderRequestPreviewDTO.HISTORICAL_REQUEST_PREVIEW,
        HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE);
  }

  /**
   * 压缩结果与其 TURN_END 在同一提交中落库；结果 Entry 的直接子 TURN_END 指向 enclosing COMPACTION TURN_START 时才构成闭合事实。
   * 缺失说明回合未完成或关联损坏，不能把它当成可预览的完成压缩。
   */
  private static Entry requireCompactionTurnEnd(Map<UUID, Entry> entries, Entry output) {
    for (Entry candidate : entries.values()) {
      if (!output.id().equals(candidate.parentEntryId())
          || !(candidate.payload() instanceof TurnEndPayload end)) {
        continue;
      }
      Entry startEntry = entries.get(end.turnStartEntryId());
      if (startEntry != null
          && startEntry.payload() instanceof TurnStartPayload start
          && start.reason() == TurnStartReason.COMPACTION
          && start.compaction() != null) {
        return candidate;
      }
    }
    throw frozenCompactionUnavailable("compaction turn is not closed");
  }

  /** join 必须就是父 TURN_START 冻结的同一子执行，且已以最终答复结束。 */
  private static void requireFrozenCompactionJoin(
      ThreadJoin join, CompactionStart frozen, UUID ownerThreadId) {
    boolean exactExecution =
        join.invocationId().equals(frozen.joinInvocationId())
            && join.delivery() == JoinDelivery.SETTLE_OWNER_TURN
            && join.parentThreadId() != null
            && join.parentThreadId().equals(ownerThreadId)
            && join.childThreadId().equals(frozen.childThreadId());
    if (!exactExecution
        || !join.matched()
        || join.terminalEntryId() == null
        || join.finalAnswerEntryId() == null
        || join.supersededByInvocationId() != null) {
      throw frozenCompactionUnavailable(
          "compaction join is not the frozen completed child execution");
    }
  }

  /** 子 Thread 必须仍属于同一冻结执行：同一父、独立 Session、runtime 创建且约束不宽于冻结预算。 */
  private static ThreadConstraints requireFrozenChildThread(
      ThreadState childThread, UUID parentSessionId, UUID ownerThreadId, CompactionStart frozen) {
    ThreadConstraints constraints = childThread.constraints();
    boolean frozenExecution =
        childThread.id().equals(frozen.childThreadId())
            && childThread.parentThreadId() != null
            && childThread.parentThreadId().equals(ownerThreadId)
            && !childThread.sessionId().equals(parentSessionId)
            && childThread.spawnTrigger() == SpawnTrigger.RUNTIME
            && constraints.promptCacheDisabled()
            && constraints.maxOutputTokens() != null
            && constraints.maxOutputTokens() <= frozen.outputBudget();
    if (!frozenExecution) {
      throw frozenCompactionUnavailable(
          "compaction child thread does not belong to the frozen execution");
    }
    return constraints;
  }

  /** finalAnswer 必须落在 join 冻结的 terminal Entry 路径上：后续续跑即使追加了新的 head 也不进入旧压缩结果。 */
  private static void requireWithinFrozenExecution(
      Map<UUID, Entry> childEntries, ThreadJoin join, Entry finalAnswer) {
    EntryPath terminalPath;
    try {
      terminalPath = entryPath(childEntries, join.terminalEntryId());
    } catch (IllegalStateException error) {
      throw frozenCompactionUnavailable(
          "compaction join terminal entry has a broken child path", error);
    }
    boolean finalAnswerOnTerminalPath =
        terminalPath.entries().stream().anyMatch(entry -> entry.id().equals(finalAnswer.id()));
    if (!finalAnswerOnTerminalPath) {
      throw frozenCompactionUnavailable(
          "compaction final answer is not within the frozen terminal path");
    }
    EntryPath answerPath = entryPath(childEntries, finalAnswer.id());
    boolean ownedByChildTurn =
        answerPath
            .openTurnStart()
            .map(Entry::payload)
            .filter(TurnStartPayload.class::isInstance)
            .map(TurnStartPayload.class::cast)
            .map(
                start ->
                    start.reason() != TurnStartReason.COMPACTION
                        && start.ownerThreadId().equals(join.childThreadId()))
            .orElse(false);
    if (!ownedByChildTurn) {
      throw frozenCompactionUnavailable(
          "compaction final answer is not owned by a child model turn");
    }
  }

  /** 冻结 child 的约束与正式提交一致：输出预算取 min，禁用 cache 时固定 NONE。 */
  private static ModelRequestSpec applyConstraints(
      ModelRequestSpec spec, ThreadConstraints constraints) {
    if (constraints.maxOutputTokens() == null && !constraints.promptCacheDisabled()) {
      return spec;
    }
    int outputTokens =
        constraints.maxOutputTokens() == null
            ? spec.outputTokens()
            : Math.min(spec.outputTokens(), constraints.maxOutputTokens());
    return new ModelRequestSpec(
        spec.providerType(),
        spec.providerConnectionGenerationId(),
        spec.model(),
        spec.variant(),
        outputTokens,
        spec.systemInstruction(),
        spec.toolBindings(),
        spec.subagentBindings(),
        constraints.promptCacheDisabled() ? ProviderCacheControl.none() : spec.cacheControl());
  }

  private static ProviderRequestPreviewUnavailableException frozenCompactionUnavailable(
      String detail) {
    return new ProviderRequestPreviewUnavailableException(Reason.PREVIEW_UNSUPPORTED, detail);
  }

  private static ProviderRequestPreviewUnavailableException frozenCompactionUnavailable(
      String detail, RuntimeException cause) {
    return new ProviderRequestPreviewUnavailableException(
        Reason.PREVIEW_UNSUPPORTED, detail, cause);
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
    if (!target.hasCursor()
        || !thread.headEntryId().equals(target.expectedHeadEntryId())
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
    if (snapshot.liveState() != ThreadLiveState.IDLE_OR_HISTORICAL) {
      throw new ProviderRequestPreviewUnavailableException(
          Reason.PREVIEW_THREAD_BUSY, "thread is not idle; the next step is not an input turn");
    }
    if (runtime.prepareAutomaticCompaction(
            thread, snapshot.requireFullPath(), compactionConfigProvider.compactionConfig(), true)
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
