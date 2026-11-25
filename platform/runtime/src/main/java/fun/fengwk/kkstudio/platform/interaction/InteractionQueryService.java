package fun.fengwk.kkstudio.platform.interaction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.api.interaction.PendingEnvironmentWait;
import fun.fengwk.kkstudio.harness.api.interaction.PendingEnvironmentWaitPage;
import fun.fengwk.kkstudio.harness.api.interaction.PendingInteraction;
import fun.fengwk.kkstudio.harness.api.interaction.PendingInteractionPage;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;
import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionOwnerDTO;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionPageDTO;
import fun.fengwk.kkstudio.platform.orchestration.ResourceAccess;
import fun.fengwk.kkstudio.platform.orchestration.ResourceOwnership.Kind;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 统一待处理交互查询用例：把 Harness 的三种只读等待事实投影为同一稳定 keyset 分页列表。
 *
 * <p>Harness 提供人工等待和按根、冻结环境聚合的可领取 Work 候选；环境服务判断 READY 租约。两者都不复制待办台账、不落地持久等待状态。
 *
 * <p>产品 owner 与根由服务端按来源 Thread 不可变的执行根解析：人工等待用 {@link HarnessRuntime#findRootThreadId}
 * 定位真实执行根，环境等待的根已由 storage 按同一列给出；随后按根 Session 的工作区归属授权；未登记的插件后台任务仅管理员可见。
 *
 * <p>两种来源共享同一 {@code (createdAt, id)} 排序键域：人工等待用调用自身坐标，环境等待用组内最早调用的坐标。本用例对两条升序流做 k 路归并，因此可以跨来源、
 * 跨页稳定分页且不重复分组，{@code limit} 只截断可见项；{@code total} 在同过滤条件下分类计数两条流，绝不把首页长度当总数。
 */
@Service
public class InteractionQueryService {

  /** 计数扫描的分页大小：total 只多付出一次有界扫描，绝不使用无界 limit 读回全部源行。 */
  static final int COUNT_PAGE_SIZE = 200;

  /** 单页最多可见条目数；越界请求立即失败，而不是静默截断。 */
  static final int MAX_LIMIT = 100;

  private final ResourceAccess access;
  private final EnvironmentService environmentService;
  private final InteractionRootResolver rootResolver;

  public InteractionQueryService(
      ResourceAccess access,
      EnvironmentService environmentService,
      ObjectProvider<HarnessRuntime> runtimes) {
    this.access = Objects.requireNonNull(access, "access");
    this.environmentService = Objects.requireNonNull(environmentService, "environmentService");
    this.rootResolver = new InteractionRootResolver(runtimes);
  }

  /**
   * 返回一页待处理 Interaction；{@code rootThreadId} 为 null 时不按根过滤，非 null 时必须是已存在的执行根（不存在为 404、非根为
   * 400）。{@code cursor} 为空表示首屏，{@code limit} 必须在 {@code [1, 100]} 内。
   *
   * <p>{@code nextCursor} 只定位本页最后一条「已消费的原始行/分组代表」，因此即使整页都被归属过滤/根过滤后为空，客户端回传同一游标也能继续向前翻页而不 漏项、不空转；
   * 两条流都耗尽时返回 {@code null}。
   */
  public InteractionPageDTO listInteractions(
      StudioPrincipal principal, UUID rootThreadId, String cursor, int limit) {
    requireValidLimit(limit);
    HarnessRuntime runtime = rootResolver.requireRuntime();
    if (rootThreadId != null) {
      requireCanonicalRoot(rootThreadId);
      if (!access.allowInteractionRoot(principal, rootThreadId)) {
        throw ResourceAccess.notFound();
      }
    }
    QueryContext context = new QueryContext(runtime, principal, rootThreadId);
    PendingPage page = new PendingPage(context, parseCursor(cursor), limit);
    page.collectVisibleItems();
    // 引擎候选包含在线环境；页满后排除尾部在线候选，避免为不存在的后续等待项产生空游标页。
    page.dropTrailingReadyCandidates();
    InteractionPageDTO dto = new InteractionPageDTO();
    dto.setItems(page.items());
    dto.setNextCursor(page.nextCursor());
    dto.setTotal(countVisibleInteractions(context));
    dto.setFreshnessAt(context.freshnessAt());
    return dto;
  }

  private static void requireValidLimit(int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw DomainException.validation("interaction", "limit must be between 1 and " + MAX_LIMIT);
    }
  }

  /**
   * 同一过滤条件下真实可见的待处理总数：对人工等待与环境等待两条流复用同一归属/根过滤与同一份请求级解析缓存，从首屏游标各分页扫描到耗尽后计数。因此既不把首页长度当总数，
   * 也不把待办复制成第二份台账，更不一次性读回全部源行。
   */
  private int countVisibleInteractions(QueryContext context) {
    return countVisibleManual(context) + countVisibleEnvironment(context);
  }

  private int countVisibleManual(QueryContext context) {
    int total = 0;
    InteractionCursor start = InteractionCursor.start();
    Instant cursorCreatedAt = start.createdAt();
    UUID cursorId = start.id();
    while (true) {
      PendingInteractionPage page =
          context.runtime().listPendingInteractions(cursorCreatedAt, cursorId, COUNT_PAGE_SIZE);
      if (page.interactions().isEmpty()) {
        return total;
      }
      for (PendingInteraction row : page.interactions()) {
        if (context.resolveManual(row) != null) {
          total++;
        }
      }
      PendingInteraction last = page.interactions().getLast();
      cursorCreatedAt = last.createdAt();
      cursorId = last.invocationId();
      if (!page.hasMore()) {
        return total;
      }
    }
  }

  private int countVisibleEnvironment(QueryContext context) {
    int total = 0;
    InteractionCursor start = InteractionCursor.start();
    Instant cursorCreatedAt = start.createdAt();
    UUID cursorId = start.id();
    while (true) {
      PendingEnvironmentWaitPage page =
          context
              .runtime()
              .listEnvironmentWaitCandidates(cursorCreatedAt, cursorId, COUNT_PAGE_SIZE);
      if (page.waits().isEmpty()) {
        return total;
      }
      for (PendingEnvironmentWait row : page.waits()) {
        if (context.resolveEnvironment(row) != null) {
          total++;
        }
      }
      PendingEnvironmentWait last = page.waits().getLast();
      cursorCreatedAt = last.representativeCreatedAt();
      cursorId = last.representativeInvocationId();
      if (!page.hasMore()) {
        return total;
      }
    }
  }

  /**
   * 一页可见项的归并过程：人工等待与环境等待两条升序流各自持有独立分页游标，按 {@code (createdAt, id)} 取较早者消费。
   *
   * <p>两路游标只在各自的队列为空且确认还有更多时推进，因此被归属/根过滤掉的原始行同样算作「已消费」，{@code nextCursor} 可稳定续页。
   */
  private final class PendingPage {

    private final QueryContext context;
    private final int limit;
    private final List<InteractionDTO> items;
    private final ArrayDeque<PendingInteraction> manualQueue = new ArrayDeque<>();
    private final ArrayDeque<PendingEnvironmentWait> environmentQueue = new ArrayDeque<>();
    private boolean manualHasMore = true;
    private boolean environmentHasMore = true;
    private Instant manualCursorCreatedAt;
    private UUID manualCursorId;
    private Instant environmentCursorCreatedAt;
    private UUID environmentCursorId;
    private Instant consumedCreatedAt;
    private UUID consumedId;

    private PendingPage(QueryContext context, InteractionCursor cursor, int limit) {
      this.context = context;
      this.limit = limit;
      this.items = new ArrayList<>(limit);
      this.manualCursorCreatedAt = cursor.createdAt();
      this.manualCursorId = cursor.id();
      this.environmentCursorCreatedAt = cursor.createdAt();
      this.environmentCursorId = cursor.id();
      this.consumedCreatedAt = cursor.createdAt();
      this.consumedId = cursor.id();
    }

    private List<InteractionDTO> items() {
      return items;
    }

    /** 归并两条升序流直到页满或两路都耗尽。 */
    private void collectVisibleItems() {
      while (items.size() < limit) {
        int remaining = limit - items.size();
        fetchManualPage(remaining);
        fetchEnvironmentPage(remaining);
        if (!consumeEarlierItem()) {
          return;
        }
      }
    }

    /** 消费两条流中较早的一项；两侧都为空表示本页已无更多候选。 */
    private boolean consumeEarlierItem() {
      PendingInteraction manual = manualQueue.peek();
      PendingEnvironmentWait environment = environmentQueue.peek();
      if (manual == null && environment == null) {
        return false;
      }
      if (manualIsEarlier(manual, environment)) {
        consumeManual(manual);
      } else {
        consumeEnvironment(environment);
      }
      return true;
    }

    private void consumeManual(PendingInteraction manual) {
      manualQueue.poll();
      consumedCreatedAt = manual.createdAt();
      consumedId = manual.invocationId();
      ResolvedRoot resolved = context.resolveManual(manual);
      if (resolved != null) {
        items.add(toManualDto(manual, resolved, context));
      }
    }

    private void consumeEnvironment(PendingEnvironmentWait environment) {
      environmentQueue.poll();
      consumedCreatedAt = environment.representativeCreatedAt();
      consumedId = environment.representativeInvocationId();
      ResolvedRoot resolved = context.resolveEnvironment(environment);
      if (resolved != null) {
        items.add(toEnvironmentDto(environment, resolved, context));
      }
    }

    /** 页满后跳过尾部仍在线（持有 READY 租约）的环境候选：它们不是待处理项，不应产生游标。 */
    private void dropTrailingReadyCandidates() {
      while (items.size() == limit) {
        fetchEnvironmentPage(COUNT_PAGE_SIZE);
        PendingEnvironmentWait candidate = environmentQueue.peek();
        if (candidate == null || context.readyLease(candidate.environmentId()).isEmpty()) {
          return;
        }
        environmentQueue.poll();
      }
    }

    private String nextCursor() {
      boolean moreRemaining =
          !manualQueue.isEmpty()
              || !environmentQueue.isEmpty()
              || manualHasMore
              || environmentHasMore;
      return moreRemaining ? InteractionCursor.encode(consumedCreatedAt, consumedId) : null;
    }

    private void fetchManualPage(int pageSize) {
      if (!manualQueue.isEmpty() || !manualHasMore) {
        return;
      }
      PendingInteractionPage page =
          context
              .runtime()
              .listPendingInteractions(manualCursorCreatedAt, manualCursorId, pageSize);
      manualHasMore = page.hasMore();
      if (page.interactions().isEmpty()) {
        return;
      }
      manualQueue.addAll(page.interactions());
      PendingInteraction last = page.interactions().getLast();
      manualCursorCreatedAt = last.createdAt();
      manualCursorId = last.invocationId();
    }

    private void fetchEnvironmentPage(int pageSize) {
      if (!environmentQueue.isEmpty() || !environmentHasMore) {
        return;
      }
      PendingEnvironmentWaitPage page =
          context
              .runtime()
              .listEnvironmentWaitCandidates(
                  environmentCursorCreatedAt, environmentCursorId, pageSize);
      environmentHasMore = page.hasMore();
      if (page.waits().isEmpty()) {
        return;
      }
      environmentQueue.addAll(page.waits());
      PendingEnvironmentWait last = page.waits().getLast();
      environmentCursorCreatedAt = last.representativeCreatedAt();
      environmentCursorId = last.representativeInvocationId();
    }
  }

  private static boolean manualIsEarlier(
      PendingInteraction manual, PendingEnvironmentWait environment) {
    return environment == null
        || (manual != null
            && compare(
                    manual.createdAt(),
                    manual.invocationId(),
                    environment.representativeCreatedAt(),
                    environment.representativeInvocationId())
                <= 0);
  }

  /** 游标形状非法与缺少参数同类：都是客户端请求形状错误。 */
  private static InteractionCursor parseCursor(String cursor) {
    try {
      return InteractionCursor.parse(cursor);
    } catch (IllegalArgumentException malformed) {
      throw DomainException.validation(
          "interaction", "cursor must be <epochMilli>:<uuid>", malformed);
    }
  }

  /** 显式根过滤值必须是 canonical 执行根：共用根解析对不存在的 Thread 抛 404，对子 Thread（自身不是链末位根）判为非法参数。 */
  private void requireCanonicalRoot(UUID rootThreadId) {
    if (!rootResolver.requireRootId(rootThreadId).equals(rootThreadId)) {
      throw DomainException.validation(
          "interaction", "rootThreadId must be an execution root: " + rootThreadId);
    }
  }

  private static int compare(
      Instant leftCreatedAt, UUID leftId, Instant rightCreatedAt, UUID rightId) {
    int timeComparison = leftCreatedAt.compareTo(rightCreatedAt);
    if (timeComparison != 0) {
      return timeComparison;
    }
    return UuidOrder.COMPARATOR.compare(leftId, rightId);
  }

  private static InteractionDTO toManualDto(
      PendingInteraction interaction, ResolvedRoot resolved, QueryContext context) {
    if (interaction.status() == ToolInvocationStatus.WAITING_APPROVAL) {
      return new InteractionDTO.Approval(
          interaction.invocationId().toString(),
          interaction.threadId().toString(),
          interaction.sessionId().toString(),
          resolved.rootThreadId().toString(),
          resolved.owner(),
          interaction.toolCallId(),
          interaction.toolName(),
          interaction.argumentsJson(),
          JsonValues.readTree(interaction.approvalJson()),
          context.queuedSameFileCount(interaction),
          interaction.createdAt());
    }
    return new InteractionDTO.Input(
        interaction.invocationId().toString(),
        interaction.threadId().toString(),
        interaction.sessionId().toString(),
        resolved.rootThreadId().toString(),
        resolved.owner(),
        interaction.toolCallId(),
        interaction.toolName(),
        interaction.argumentsJson(),
        interaction.createdAt());
  }

  private InteractionDTO toEnvironmentDto(
      PendingEnvironmentWait environmentWait, ResolvedRoot resolved, QueryContext context) {
    String environmentId = environmentWait.environmentId().value().toString();
    return new InteractionDTO.EnvironmentWait(
        resolved.rootThreadId().toString(),
        resolved.owner(),
        environmentId,
        context.resolveEnvironmentName(environmentId),
        environmentWait.waitingCount(),
        environmentWait.representativeCreatedAt());
  }

  /** 请求级解析上下文：按来源 Thread 缓存根、按根缓存 owner、按环境缓存展示名，避免跨来源重复解析与重复快照。 */
  private final class QueryContext {

    private final HarnessRuntime runtime;
    private final StudioPrincipal principal;
    private final UUID filterRootThreadId;
    private final Map<UUID, UUID> rootBySourceThread = new HashMap<>();
    private final Map<UUID, Optional<InteractionOwnerDTO>> ownerByRootThread = new HashMap<>();
    private final Map<UUID, ThreadSnapshot> rootSnapshotByRootThread = new HashMap<>();
    private final Map<String, String> environmentNameById = new HashMap<>();
    private final Map<UUID, Integer> queuedSameFileCountByInvocation = new HashMap<>();
    private final Map<EnvironmentId, Optional<Instant>> readyLeaseById = new HashMap<>();

    private QueryContext(
        HarnessRuntime runtime, StudioPrincipal principal, UUID filterRootThreadId) {
      this.runtime = runtime;
      this.principal = Objects.requireNonNull(principal, "principal");
      this.filterRootThreadId = filterRootThreadId;
    }

    HarnessRuntime runtime() {
      return runtime;
    }

    /** 当前 edit/write 审批之后仍未结束的同批同文件修改数。 */
    int queuedSameFileCount(PendingInteraction interaction) {
      if (!"edit".equals(interaction.toolName()) && !"write".equals(interaction.toolName())) {
        return 0;
      }
      return queuedSameFileCountByInvocation.computeIfAbsent(
          interaction.invocationId(), runtime::countQueuedSameFileMutationsAfter);
    }

    /** 人工等待：沿来源不可变祖先链解析真实根、可选根过滤与产品 owner；根无产品归属时返回 null。 */
    ResolvedRoot resolveManual(PendingInteraction row) {
      UUID rootThreadId =
          rootBySourceThread.computeIfAbsent(row.threadId(), rootResolver::requireRootId);
      if (filterRootThreadId != null && !filterRootThreadId.equals(rootThreadId)) {
        return null;
      }
      Optional<InteractionOwnerDTO> owner =
          ownerByRootThread.computeIfAbsent(rootThreadId, id -> resolveOwnerForManual(id, row));
      return owner.map(value -> new ResolvedRoot(rootThreadId, value)).orElse(null);
    }

    /** 环境等待：根已由 storage 递归解析，这里只做根过滤与 owner 解析。 */
    ResolvedRoot resolveEnvironment(PendingEnvironmentWait row) {
      UUID rootThreadId = row.rootThreadId();
      if (filterRootThreadId != null && !filterRootThreadId.equals(rootThreadId)) {
        return null;
      }
      Optional<InteractionOwnerDTO> owner =
          ownerByRootThread.computeIfAbsent(rootThreadId, this::resolveOwnerForRoot);
      if (owner.isEmpty() || readyLease(row.environmentId()).isPresent()) {
        return null;
      }
      return owner.map(value -> new ResolvedRoot(rootThreadId, value)).orElse(null);
    }

    /** 来源自身即根时复用行内 Session，后代来源回读根快照取得根 Session。 */
    private Optional<InteractionOwnerDTO> resolveOwnerForManual(
        UUID rootThreadId, PendingInteraction row) {
      UUID rootSessionId =
          rootThreadId.equals(row.threadId()) ? row.sessionId() : rootSessionId(rootThreadId);
      return ownerForRootSession(rootThreadId, rootSessionId);
    }

    /** 环境等待的来源可能位于任意深度，这里统一回读根快照取得根 Session 再解析 owner。 */
    private Optional<InteractionOwnerDTO> resolveOwnerForRoot(UUID rootThreadId) {
      return ownerForRootSession(rootThreadId, rootSessionId(rootThreadId));
    }

    /** 每个根只回读一次快照：根 Session 用于归属解析，根名称用于展示；后续条目直接命中缓存。 */
    private UUID rootSessionId(UUID rootThreadId) {
      return rootSnapshot(rootThreadId).thread().sessionId();
    }

    private ThreadSnapshot rootSnapshot(UUID rootThreadId) {
      return rootSnapshotByRootThread.computeIfAbsent(rootThreadId, runtime::getThreadSnapshot);
    }

    private Optional<InteractionOwnerDTO> ownerForRootSession(
        UUID rootThreadId, UUID rootSessionId) {
      var placement = access.owner(Kind.SESSION, rootSessionId);
      if (!ResourceAccess.allowsInteraction(
          principal,
          placement,
          () -> rootSnapshot(rootThreadId).thread().kind() == ThreadKind.PLUGIN_TASK)) {
        return Optional.empty();
      }
      InteractionOwnerDTO owner = new InteractionOwnerDTO();
      if (placement.isPresent()) {
        var space = placement.get();
        owner.setKind("CHAT_SPACE");
        owner.setId(space.spaceId().toString());
        owner.setTitle(space.spaceTitle());
        owner.setLink("/chat-spaces/" + space.spaceId());
      } else {
        ThreadSnapshot root = rootSnapshot(rootThreadId);
        owner.setKind("AGENT_TASK");
        owner.setId(rootThreadId.toString());
        String agentName = root.entryState().baseSettings().agentName();
        owner.setTitle(agentName != null ? agentName : root.thread().name());
      }
      return Optional.of(owner);
    }

    /** 环境展示名只按环境 id 从注册表补齐一次（按页去重），绝不按当前 composer 环境猜测，也不逐卡快照。 */
    private String resolveEnvironmentName(String environmentId) {
      return environmentNameById.computeIfAbsent(
          environmentId, id -> environmentService.requireName(EnvironmentId.parse(id)));
    }

    private Optional<Instant> readyLease(EnvironmentId environmentId) {
      return readyLeaseById.computeIfAbsent(environmentId, environmentService::findReadyLeaseUntil);
    }

    private Instant freshnessAt() {
      Instant earliest = runtime.findNextEnvironmentWorkChange().orElse(null);
      for (EnvironmentId id : runtime.listReadyToolEnvironments()) {
        Instant leaseUntil = readyLease(id).orElse(null);
        if (leaseUntil != null && (earliest == null || leaseUntil.isBefore(earliest))) {
          earliest = leaseUntil;
        }
      }
      return earliest;
    }
  }

  /** 已解析的来源投影：真实根与其产品 owner。 */
  private record ResolvedRoot(UUID rootThreadId, InteractionOwnerDTO owner) {}
}
