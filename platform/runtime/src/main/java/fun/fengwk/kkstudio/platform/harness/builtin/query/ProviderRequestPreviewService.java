package fun.fengwk.kkstudio.platform.harness.builtin.query;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessProviderRequestPreviewDTO;
import fun.fengwk.kkstudio.platform.harness.model.DatabaseProviderResolutionService;
import fun.fengwk.kkstudio.platform.harness.model.ProviderResolutionService;
import fun.fengwk.kkstudio.platform.harness.thread.command.DatabaseTurnResolver;
import fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 历史模型输出的 Provider 协议请求体重建：在只读事实上用正式发送路径的规划、物化、Provider 解析与协议编码生成请求体，不写任何 durable 状态、不触发 transport。
 *
 * <p>请求前缀严格是 ROOT 到该输出的 parent，并把该输出的记录时间显式交给规划，绝不用当前时间冒充。冻结请求来自同一 {@link
 * DatabaseTurnResolver#planHistorical(UUID, UUID, EntryPath)}，有效请求来自同一 {@link
 * DatabaseProviderResolutionService#resolve}，最终字节来自同一 {@link ProviderAdapter#encodeRequestBody}。压缩
 * Turn 也是真实模型输出： 按其 durable COMPACTION TURN_START 冻结的 executionModel 与预算走同一条物化/编码路径。
 */
public final class ProviderRequestPreviewService {

  private final HarnessRuntime runtime;
  private final DatabaseTurnResolver turnResolver;
  private final DatabaseProviderResolutionService providerResolution;
  private final ModelRequestMaterializer materializer = new ModelRequestMaterializer();

  public ProviderRequestPreviewService(
      HarnessRuntime runtime,
      DatabaseTurnResolver turnResolver,
      DatabaseProviderResolutionService providerResolution) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.turnResolver = Objects.requireNonNull(turnResolver, "turnResolver");
    this.providerResolution = Objects.requireNonNull(providerResolution, "providerResolution");
  }

  /**
   * 现算一次历史模型输出的 Provider 协议请求体预览：请求前缀严格是 ROOT 到该输出的 parent，规划时间显式取该输出的记录时间。
   *
   * <p>catalog 与 Provider 配置都已不是当时的那一份，因此这里按当前定义重建；响应以 {@link
   * HarnessProviderRequestPreviewDTO#HISTORICAL_NOTICE} 明确声明它不等于原始发送字节。
   *
   * <p>父 COMPACTION 结果自身不创建 ModelInvocation：它的摘要请求由独立的压缩子 Thread 发出。此时沿父 COMPACTION TURN_START 冻结的
   * child Thread / join 票据定位子执行，并以 join 冻结的 finalAnswerEntryId（而不是 child 当前
   * head）找到真正产出该摘要的最后一次调用前缀；工具 loop 与输出预算按子执行重建，缓存使用所选 variant。关联缺失或不属于同一冻结执行时 typed 拒绝，绝不猜测。
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
    if (!BuiltinExecution.DRIVER.equals(requestPath.baseSettings().execution().driver())) {
      throw DomainException.conflict(
          "PREVIEW_UNSUPPORTED",
          "provider request preview is only supported for builtin execution driver");
    }
    Instant recordedAt = output.createdAt();
    // 历史路径可能不属于任何一个现存 Thread；规划本身不回查 Thread 归属，因此这里同样只给 candidate id。
    UUID candidateThreadId = UUID.randomUUID();
    return planAndEncode(
        turnResolver.planHistorical(candidateThreadId, sessionId, requestPath),
        requestPath,
        recordedAt,
        requestHeadEntryId);
  }

  /**
   * 父 COMPACTION 结果 -> 冻结压缩子执行的真实最后一次调用。
   *
   * <p>父 TURN_START 冻结 child Thread 与 join 票据；join 的 finalAnswerEntryId 是这次压缩执行的最终答复边界。这里只沿
   * finalAnswer 的 parent（该次调用的 request head）构造前缀，绝不使用 child 当前 head，因此同一条 child Thread 上后续续跑
   * 不会污染旧压缩结果。child Thread 的不可变执行约束在规划后再次收紧输出预算，与正式提交路径一致。
   */
  private HarnessProviderRequestPreviewDTO previewCompactionChild(
      UUID parentSessionId, Map<UUID, Entry> parentEntries, Entry output) {
    EntryPath outputPath = entryPath(parentEntries, output.id());
    CompactionTurnClosure closure = requireCompactionTurnEnd(parentEntries, output);
    List<Entry> closedEntries = new ArrayList<>(outputPath.entries());
    closedEntries.add(closure.turnEnd());
    EntryPath closedPath;
    try {
      closedPath = new EntryPath(closedEntries);
    } catch (IllegalArgumentException error) {
      throw frozenCompactionUnavailable(
          "compaction turn end does not belong to the result path", error);
    }
    CompactionStart frozen;
    try {
      frozen = runtime.findCompactionTurnStart(closedPath, output.id());
    } catch (IllegalStateException error) {
      throw frozenCompactionUnavailable(
          "compaction result has no enclosing COMPACTION turn", error);
    }
    if (!BuiltinExecution.DRIVER.equals(frozen.execution().driver())) {
      throw frozenCompactionUnavailable("compaction execution driver is not builtin");
    }
    UUID ownerThreadId = closure.turnStart().ownerThreadId();
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
    } catch (DomainException error) {
      if (error.code() != DomainErrorCode.NOT_FOUND || !"thread".equals(error.resource())) {
        throw error;
      }
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
        turnResolver.planHistorical(frozen.childThreadId(), parentSessionId, requestPath);
    if (plan instanceof LiveTurnPlan.Planned planned) {
      plan =
          new LiveTurnPlan.Planned(
              applyConstraints(planned.spec(), constraints),
              planned.contextWindow(),
              planned.candidateTools(),
              planned.skills());
    }
    return planAndEncode(plan, requestPath, recordedAt, requestHeadEntryId);
  }

  /**
   * 压缩结果与其 TURN_END 在同一提交中落库；结果 Entry 的直接子 TURN_END 指向 enclosing COMPACTION TURN_START 时才构成闭合事实。
   * 缺失说明回合未完成或关联损坏，不能把它当成可预览的完成压缩。
   *
   * <p>返回的 TURN_START 就是该结果的 durable 归属事实：owner Thread 与冻结元数据都由它派生，调用方不再另找来源。
   */
  private static CompactionTurnClosure requireCompactionTurnEnd(
      Map<UUID, Entry> entries, Entry output) {
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
        return new CompactionTurnClosure(candidate, start);
      }
    }
    throw frozenCompactionUnavailable("compaction turn is not closed");
  }

  /**
   * 已闭合 COMPACTION turn 的两个 durable 端点：结果 Entry 的直接子 TURN_END 与其 enclosing COMPACTION TURN_START。
   */
  private record CompactionTurnClosure(Entry turnEnd, TurnStartPayload turnStart) {}

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
            && childThread.kind() == ThreadKind.COMPACTION
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

  /** 冻结 child 的约束与正式提交一致：输出预算取 min。 */
  private static ModelRequestSpec applyConstraints(
      ModelRequestSpec spec, ThreadConstraints constraints) {
    if (constraints.maxOutputTokens() == null) {
      return spec;
    }
    int outputTokens = Math.min(spec.outputTokens(), constraints.maxOutputTokens());
    if (outputTokens == spec.outputTokens()) {
      return spec;
    }
    return new ModelRequestSpec(
        spec.providerType(),
        spec.model(),
        spec.variant(),
        outputTokens,
        spec.systemInstruction(),
        spec.toolBindings(),
        spec.subagentBindings(),
        spec.groupExecution(),
        spec.systemSections());
  }

  private static DomainException frozenCompactionUnavailable(String detail) {
    return DomainException.conflict("PREVIEW_UNSUPPORTED", detail);
  }

  private static DomainException frozenCompactionUnavailable(
      String detail, RuntimeException cause) {
    return DomainException.conflict("PREVIEW_UNSUPPORTED", detail, cause);
  }

  /** 历史重建共用的只读尾部：冻结规划 -> 物化 -> 正式协议编码 -> 稳定响应投影。 */
  private HarnessProviderRequestPreviewDTO planAndEncode(
      LiveTurnPlan plan, EntryPath candidatePath, Instant plannedAt, UUID sourceHeadEntryId) {
    if (plan instanceof LiveTurnPlan.Rejected) {
      // 规划的错误码与自由文本详情都不属于公开预览协议。
      throw DomainException.conflict(
          "PREVIEW_PLANNING_FAILED", "request cannot be planned for preview");
    }
    ModelRequestSpec spec = ((LiveTurnPlan.Planned) plan).spec();
    ModelRequestSpec execution = spec.groupExecution() == null ? spec : spec.forMember(0);
    ProviderRequest request = materializer.materialize(candidatePath, execution);
    byte[] body = encodeRequestBody(execution.providerType(), execution, request);

    HarnessProviderRequestPreviewDTO dto = new HarnessProviderRequestPreviewDTO();
    dto.setKind(HarnessProviderRequestPreviewDTO.Kind.HISTORICAL_REQUEST_PREVIEW);
    dto.setGeneratedAt(plannedAt);
    dto.setProviderType(spec.providerType().name());
    dto.setModelName(spec.model().modelName());
    dto.setBodyByteSize(body.length);
    dto.setBodyJson(new String(body, StandardCharsets.UTF_8));
    dto.setSourceHeadEntryId(sourceHeadEntryId.toString());
    dto.setNotice(HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE);
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
      throw DomainException.validation(
          "entry", name + " does not belong to the session: " + entryId);
    }
    return entry;
  }

  /**
   * 历史预览只接受模型输出本身：携带 assistantMetadata 的 ASSISTANT MESSAGE。请求前缀就是它的 parent，因此本次输出与未来历史绝不进入 请求体；父
   * COMPACTION 结果已在 {@link #previewHistorical} 前置拒绝，不会走到这里。
   */
  private static UUID requireRequestHead(Entry output) {
    if (!carriesModelOutputMetadata(output)) {
      throw DomainException.validation(
          "entry", "provider request preview requires an assistant model output entry");
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

  /** Provider 解析与编码：type 漂移、adapter 缺失一律确定性拒绝；不支持预览的 adapter 绝不伪装成空体。 */
  private byte[] encodeRequestBody(
      ProviderType providerType, ModelRequestSpec spec, ProviderRequest request) {
    ProviderResolutionService.ResolvedExecution resolved;
    try {
      resolved = providerResolution.resolve(providerType, request);
    } catch (IllegalArgumentException | IllegalStateException error) {
      throw DomainException.conflict(
          "PREVIEW_PROVIDER_UNAVAILABLE", "cannot resolve the current provider for preview");
    }
    try {
      return resolved.encodeRequestBody();
    } catch (UnsupportedOperationException error) {
      throw DomainException.conflict(
          "PREVIEW_UNSUPPORTED", providerType + " adapter does not support request body preview");
    } catch (ProviderException error) {
      // 编码失败的原因文本可能携带请求细节，这里只保留稳定的失败类别。
      throw DomainException.conflict(
          "PREVIEW_ENCODING_FAILED", "request body cannot be encoded for preview", error);
    }
  }
}
