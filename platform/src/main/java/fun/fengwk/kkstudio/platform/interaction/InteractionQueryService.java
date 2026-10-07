package fun.fengwk.kkstudio.platform.interaction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingEnvironmentWait;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingEnvironmentWaitPage;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteraction;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteractionPage;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.UuidOrder;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSession;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionOwnerDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionPageDTO;

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
 * <p>事实源只有 Harness：{@code WAITING_INPUT} / {@code WAITING_APPROVAL} 的 ToolInvocation（人工等待），以及
 * {@link HarnessRuntime#listPendingEnvironmentWaits} 提供的「READY 调用 + 到期且无有效执行 lease 的 TOOL Work +
 * 所需环境无有效 READY 连接租约」聚合（环境等待）。两者都不复制待办台账、不落地持久等待状态。
 *
 * <p>产品 owner 与根由服务端沿来源 Thread 的不可变祖先链解析：人工等待先用 {@link HarnessRuntime#findAncestorChain}
 * 定位真实执行根，环境等待的根 已由 storage 递归解析；随后读取根的 Session（Chat）或根 Thread
 * 绑定（Issue+Agent）。两者都无法解析的根不对外暴露，绝不信任客户端传入归属。
 *
 * <p>两种来源共享同一 {@code (createdAt, id)} 排序键域：人工等待用调用自身坐标，环境等待用组内最早调用的坐标。本用例对两条升序流做 k 路归并，因此可以跨来源、
 * 跨页稳定分页且不重复分组，{@code limit} 只截断可见项；{@code total} 在同过滤条件下分类计数两条流，绝不把首页长度当总数。
 */
@Service
public class InteractionQueryService {

  /** 计数扫描的分页大小：total 只多付出一次有界扫描，绝不使用无界 limit 读回全部源行。 */
  static final int COUNT_PAGE_SIZE = 200;

  private static final String TYPE_INPUT = "INPUT";
  private static final String TYPE_APPROVAL = "APPROVAL";
  private static final String TYPE_ENVIRONMENT_WAIT = "ENVIRONMENT_WAIT";

  private final ChatSessionRepository chatSessionRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final EnvironmentRepository environmentRepository;
  private final InteractionRootResolver rootResolver;

  public InteractionQueryService(
      ChatSessionRepository chatSessionRepository,
      IssueAgentThreadRepository issueAgentThreadRepository,
      EnvironmentRepository environmentRepository,
      ObjectProvider<HarnessRuntime> runtimes) {
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.rootResolver = new InteractionRootResolver(runtimes);
  }

  /**
   * 返回一页待处理 Interaction；{@code rootThreadId} 为 null 时不按根过滤，非 null 时必须是已存在的执行根（不存在为 404、非根为
   * 400）。{@code cursor} 为空表示首屏，{@code limit} 必须为正（调用方已在边界校验上限）。
   *
   * <p>{@code nextCursor} 只定位本页最后一条「已消费的原始行/分组代表」，因此即使整页都被归属过滤/根过滤后为空，客户端回传同一游标也能继续向前翻页而不 漏项、不空转；
   * 两条流都耗尽时返回 {@code null}。
   */
  public InteractionPageDTO listInteractions(UUID rootThreadId, String cursor, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    HarnessRuntime runtime = rootResolver.requireRuntime();
    if (rootThreadId != null) {
      requireCanonicalRoot(rootThreadId);
    }
    InteractionCursor decoded = InteractionCursor.parse(cursor);
    QueryContext context = new QueryContext(runtime, rootThreadId);
    List<InteractionDTO> items = new ArrayList<>(limit);
    Instant consumedCreatedAt = decoded.createdAt();
    UUID consumedId = decoded.id();
    ArrayDeque<PendingInteraction> manualQueue = new ArrayDeque<>();
    ArrayDeque<PendingEnvironmentWait> environmentQueue = new ArrayDeque<>();
    Instant manualCursorCreatedAt = decoded.createdAt();
    UUID manualCursorId = decoded.id();
    Instant environmentCursorCreatedAt = decoded.createdAt();
    UUID environmentCursorId = decoded.id();
    boolean manualHasMore = true;
    boolean environmentHasMore = true;
    while (items.size() < limit) {
      int remaining = limit - items.size();
      if (manualQueue.isEmpty() && manualHasMore) {
        PendingInteractionPage page =
            runtime.listPendingInteractions(manualCursorCreatedAt, manualCursorId, remaining);
        manualHasMore = page.hasMore();
        if (!page.interactions().isEmpty()) {
          manualQueue.addAll(page.interactions());
          PendingInteraction last = page.interactions().get(page.interactions().size() - 1);
          manualCursorCreatedAt = last.createdAt();
          manualCursorId = last.invocationId();
        }
      }
      if (environmentQueue.isEmpty() && environmentHasMore) {
        PendingEnvironmentWaitPage page =
            runtime.listPendingEnvironmentWaits(
                environmentCursorCreatedAt, environmentCursorId, remaining);
        environmentHasMore = page.hasMore();
        if (!page.waits().isEmpty()) {
          environmentQueue.addAll(page.waits());
          PendingEnvironmentWait last = page.waits().get(page.waits().size() - 1);
          environmentCursorCreatedAt = last.representativeCreatedAt();
          environmentCursorId = last.representativeInvocationId();
        }
      }
      PendingInteraction manual = manualQueue.peek();
      PendingEnvironmentWait environment = environmentQueue.peek();
      if (manual == null && environment == null) {
        break;
      }
      if (environment == null
          || (manual != null
              && compare(
                      manual.createdAt(),
                      manual.invocationId(),
                      environment.representativeCreatedAt(),
                      environment.representativeInvocationId())
                  <= 0)) {
        manualQueue.poll();
        consumedCreatedAt = manual.createdAt();
        consumedId = manual.invocationId();
        ResolvedRoot resolved = context.resolveManual(manual);
        if (resolved != null) {
          items.add(toManualDto(manual, resolved));
        }
      } else {
        environmentQueue.poll();
        consumedCreatedAt = environment.representativeCreatedAt();
        consumedId = environment.representativeInvocationId();
        ResolvedRoot resolved = context.resolveEnvironment(environment);
        if (resolved != null) {
          items.add(toEnvironmentDto(environment, resolved, context));
        }
      }
    }
    boolean moreRemaining =
        !manualQueue.isEmpty()
            || !environmentQueue.isEmpty()
            || manualHasMore
            || environmentHasMore;
    InteractionPageDTO dto = new InteractionPageDTO();
    dto.setItems(items);
    dto.setNextCursor(
        moreRemaining ? InteractionCursor.encode(consumedCreatedAt, consumedId) : null);
    dto.setTotal(countVisibleInteractions(context));
    return dto;
  }

  /**
   * 同一过滤条件下真实可见的待处理总数：对人工等待与环境等待两条流复用同一归属/根过滤与同一份请求级解析缓存，从首屏游标各分页扫描到耗尽后计数。因此既不把首页长度当总数，
   * 也不把待办复制成第二份台账，更不一次性读回全部源行。
   */
  private int countVisibleInteractions(QueryContext context) {
    int total = 0;
    InteractionCursor start = InteractionCursor.start();
    Instant manualCursorCreatedAt = start.createdAt();
    UUID manualCursorId = start.id();
    while (true) {
      PendingInteractionPage page =
          context
              .runtime()
              .listPendingInteractions(manualCursorCreatedAt, manualCursorId, COUNT_PAGE_SIZE);
      if (page.interactions().isEmpty()) {
        break;
      }
      for (PendingInteraction row : page.interactions()) {
        if (context.resolveManual(row) != null) {
          total++;
        }
      }
      PendingInteraction last = page.interactions().get(page.interactions().size() - 1);
      manualCursorCreatedAt = last.createdAt();
      manualCursorId = last.invocationId();
      if (!page.hasMore()) {
        break;
      }
    }
    Instant environmentCursorCreatedAt = start.createdAt();
    UUID environmentCursorId = start.id();
    while (true) {
      PendingEnvironmentWaitPage page =
          context
              .runtime()
              .listPendingEnvironmentWaits(
                  environmentCursorCreatedAt, environmentCursorId, COUNT_PAGE_SIZE);
      if (page.waits().isEmpty()) {
        break;
      }
      for (PendingEnvironmentWait row : page.waits()) {
        if (context.resolveEnvironment(row) != null) {
          total++;
        }
      }
      PendingEnvironmentWait last = page.waits().get(page.waits().size() - 1);
      environmentCursorCreatedAt = last.representativeCreatedAt();
      environmentCursorId = last.representativeInvocationId();
      if (!page.hasMore()) {
        break;
      }
    }
    return total;
  }

  /** 显式根过滤值必须是 canonical 执行根：共用根解析对不存在的 Thread 抛 404，对子 Thread（自身不是链末位根）判为非法参数。 */
  private void requireCanonicalRoot(UUID rootThreadId) {
    if (!rootResolver.requireRootId(rootThreadId).equals(rootThreadId)) {
      throw new IllegalArgumentException("rootThreadId must be an execution root: " + rootThreadId);
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

  private static InteractionDTO toManualDto(PendingInteraction interaction, ResolvedRoot resolved) {
    InteractionDTO dto = new InteractionDTO();
    dto.setType(
        interaction.status() == ToolInvocationStatus.WAITING_APPROVAL ? TYPE_APPROVAL : TYPE_INPUT);
    dto.setInteractionId(interaction.invocationId().toString());
    dto.setStatus(interaction.status().name());
    dto.setThreadId(interaction.threadId().toString());
    dto.setSessionId(interaction.sessionId().toString());
    dto.setRootThreadId(resolved.rootThreadId().toString());
    dto.setOwner(resolved.owner());
    dto.setToolCallId(interaction.toolCallId());
    dto.setToolName(interaction.toolName());
    dto.setArgumentsJson(interaction.argumentsJson());
    dto.setApprovalJson(interaction.approvalJson());
    dto.setCreateTime(interaction.createdAt());
    return dto;
  }

  private InteractionDTO toEnvironmentDto(
      PendingEnvironmentWait environmentWait, ResolvedRoot resolved, QueryContext context) {
    InteractionDTO dto = new InteractionDTO();
    dto.setType(TYPE_ENVIRONMENT_WAIT);
    dto.setRootThreadId(resolved.rootThreadId().toString());
    dto.setOwner(resolved.owner());
    String environmentId = environmentWait.environmentId().value().toString();
    dto.setEnvironmentId(environmentId);
    dto.setEnvironmentName(context.resolveEnvironmentName(environmentId));
    dto.setWaitingCount(environmentWait.waitingCount());
    dto.setCreateTime(environmentWait.representativeCreatedAt());
    return dto;
  }

  /** 请求级解析上下文：按来源 Thread 缓存根、按根缓存 owner、按环境缓存展示名，避免跨来源重复解析与重复快照。 */
  private final class QueryContext {

    private final HarnessRuntime runtime;
    private final UUID filterRootThreadId;
    private final Map<UUID, UUID> rootBySourceThread = new HashMap<>();
    private final Map<UUID, Optional<InteractionOwnerDTO>> ownerByRootThread = new HashMap<>();
    private final Map<String, String> environmentNameById = new HashMap<>();

    private QueryContext(HarnessRuntime runtime, UUID filterRootThreadId) {
      this.runtime = runtime;
      this.filterRootThreadId = filterRootThreadId;
    }

    HarnessRuntime runtime() {
      return runtime;
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
      return owner.map(value -> new ResolvedRoot(rootThreadId, value)).orElse(null);
    }

    /**
     * 产品 owner 只按根解析：根 Session 命中 {@code chat_session} 即为 Chat；根 Thread 命中 {@code
     * project_issue_agent_thread} 即为 Issue+Agent。来源自身即根时直接复用行内 Session，后代来源才回读根快照取得根 Session。
     */
    private Optional<InteractionOwnerDTO> resolveOwnerForManual(
        UUID rootThreadId, PendingInteraction row) {
      UUID rootSessionId =
          rootThreadId.equals(row.threadId())
              ? row.sessionId()
              : runtime.getThreadSnapshot(rootThreadId).thread().sessionId();
      return ownerForRootSession(rootThreadId, rootSessionId);
    }

    /** 环境等待的来源可能位于任意深度，这里统一回读根快照取得根 Session 再解析 owner。 */
    private Optional<InteractionOwnerDTO> resolveOwnerForRoot(UUID rootThreadId) {
      UUID rootSessionId = runtime.getThreadSnapshot(rootThreadId).thread().sessionId();
      return ownerForRootSession(rootThreadId, rootSessionId);
    }

    private Optional<InteractionOwnerDTO> ownerForRootSession(
        UUID rootThreadId, UUID rootSessionId) {
      ChatSession chatSession = chatSessionRepository.findBySessionId(rootSessionId);
      if (chatSession != null) {
        InteractionOwnerDTO owner = new InteractionOwnerDTO();
        owner.setType("CHAT");
        owner.setChatId(chatSession.chatId().toString());
        return Optional.of(owner);
      }
      IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(rootThreadId);
      if (binding != null) {
        InteractionOwnerDTO owner = new InteractionOwnerDTO();
        owner.setType("ISSUE_AGENT");
        owner.setIssueId(binding.issueId().toString());
        owner.setAgentName(binding.agentName());
        return Optional.of(owner);
      }
      return Optional.empty();
    }

    /** 环境展示名只按环境 id 从注册表补齐一次（按页去重），绝不按当前 composer 环境猜测，也不逐卡快照。 */
    private String resolveEnvironmentName(String environmentId) {
      return environmentNameById.computeIfAbsent(
          environmentId,
          id -> {
            Environment environment = environmentRepository.getById(UUID.fromString(id));
            if (environment == null) {
              throw new IllegalStateException(
                  "waiting environment registry entry does not exist: " + id);
            }
            return environment.getName();
          });
    }
  }

  /** 已解析的来源投影：真实根与其产品 owner。 */
  private record ResolvedRoot(UUID rootThreadId, InteractionOwnerDTO owner) {}
}
