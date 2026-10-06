package fun.fengwk.kkstudio.platform.interaction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteraction;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteractionPage;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSession;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionOwnerDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionPageDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 统一待处理交互查询用例。
 *
 * <p>事实源只有 Harness 两种等待状态：{@code WAITING_INPUT} 与 {@code WAITING_APPROVAL} 的
 * Invocation，不复制待办、不按状态拆成两次查询。产品 owner 与根由服务端沿来源 Thread 的不可变祖先链解析：先用 {@link
 * HarnessRuntime#findAncestorChain} 定位真实执行根，再读取根的 Session（Chat）或根 Thread 绑定（Issue+Agent），因此没有直接
 * 产品绑定的后代任务照常可见。两者都无法解析的 Thread（内部委派且无根归属）不对外暴露。当前沿用单用户认证边界，不伪造用户表，也不再接受客户端传入 owner/身份。
 *
 * <p>分页按 {@code (createdAt, id)} keyset 稳定升序。因为待处理行需要先按归属解析、可选按根过滤再对外暴露，本用例必须持续向后扫描：每轮按剩余额度取原始行，用
 * 本页最后一行推进游标，直到凑满 {@code limit} 条可见项或源已耗尽，绝不因为被过滤项而提前截断返回。
 *
 * <p>{@code total} 是同一过滤条件下真实可见的待处理总数：复用同一 keyset 查询与同一归属/根过滤，按 {@link #COUNT_PAGE_SIZE}
 * 分页扫描源行后计数，既不把首页长度当成总数，也不把待办复制成第二份台账，更不一次性读回全部 Invocation。
 */
@Service
public class InteractionQueryService {

  /** 计数扫描的分页大小：total 只多付出一次有界扫描，绝不使用无界 limit 读回全部源行。 */
  static final int COUNT_PAGE_SIZE = 200;

  private final ChatSessionRepository chatSessionRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final InteractionRootResolver rootResolver;

  public InteractionQueryService(
      ChatSessionRepository chatSessionRepository,
      IssueAgentThreadRepository issueAgentThreadRepository,
      ObjectProvider<HarnessRuntime> runtimes) {
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.rootResolver = new InteractionRootResolver(runtimes);
  }

  /**
   * 返回一页待处理 Interaction；{@code rootThreadId} 为 null 时不按根过滤，非 null 时必须是已存在的执行根（不存在为 404、非根为
   * 400）。{@code cursor} 为空表示首屏，{@code limit} 必须为正（调用方已在边界校验上限）。
   *
   * <p>{@code nextCursor} 只定位本页最后一条「已消费的原始行」，因此即使整页都被归属过滤/根过滤后为空，客户端回传同一游标也能继续向前翻页而不 漏项、不空转；源耗尽时返回
   * {@code null}。
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
    Instant afterCreatedAt = decoded.createdAt();
    UUID afterId = decoded.id();
    List<InteractionDTO> items = new ArrayList<>(limit);
    // 同一执行树可能贡献多行、跨多轮扫描：按来源 Thread 缓存根、按根缓存 owner，避免重复解析与重复快照。
    Map<UUID, UUID> rootBySourceThread = new HashMap<>();
    Map<UUID, Optional<InteractionOwnerDTO>> ownerByRootThread = new HashMap<>();
    boolean exhausted = false;
    while (items.size() < limit) {
      PendingInteractionPage page =
          runtime.listPendingInteractions(afterCreatedAt, afterId, limit - items.size());
      List<PendingInteraction> rows = page.interactions();
      if (rows.isEmpty()) {
        exhausted = true;
        break;
      }
      PendingInteraction lastConsumed = rows.get(rows.size() - 1);
      afterCreatedAt = lastConsumed.createdAt();
      afterId = lastConsumed.invocationId();
      for (PendingInteraction row : rows) {
        ResolvedRoot resolved =
            resolveRoot(runtime, row, rootThreadId, rootBySourceThread, ownerByRootThread);
        if (resolved == null) {
          continue;
        }
        items.add(toDto(row, resolved));
      }
      if (!page.hasMore()) {
        exhausted = true;
        break;
      }
    }
    InteractionPageDTO dto = new InteractionPageDTO();
    dto.setItems(items);
    dto.setNextCursor(exhausted ? null : InteractionCursor.encode(afterCreatedAt, afterId));
    dto.setTotal(
        countVisibleInteractions(runtime, rootThreadId, rootBySourceThread, ownerByRootThread));
    return dto;
  }

  /**
   * 同一过滤条件下真实可见的待处理总数。
   *
   * <p>复用列表的 keyset 查询、同一份根/owner 解析过滤与同一份请求级解析缓存，从首屏游标按 {@link #COUNT_PAGE_SIZE}
   * 逐页推进到源耗尽后计数；因此既不需要客户端台账，也不会重复解析根或退化成 N+1 回读。
   */
  private int countVisibleInteractions(
      HarnessRuntime runtime,
      UUID filterRootThreadId,
      Map<UUID, UUID> rootBySourceThread,
      Map<UUID, Optional<InteractionOwnerDTO>> ownerByRootThread) {
    InteractionCursor start = InteractionCursor.start();
    Instant afterCreatedAt = start.createdAt();
    UUID afterId = start.id();
    int total = 0;
    while (true) {
      PendingInteractionPage page =
          runtime.listPendingInteractions(afterCreatedAt, afterId, COUNT_PAGE_SIZE);
      List<PendingInteraction> rows = page.interactions();
      if (rows.isEmpty()) {
        return total;
      }
      PendingInteraction lastConsumed = rows.get(rows.size() - 1);
      afterCreatedAt = lastConsumed.createdAt();
      afterId = lastConsumed.invocationId();
      for (PendingInteraction row : rows) {
        ResolvedRoot resolved =
            resolveRoot(runtime, row, filterRootThreadId, rootBySourceThread, ownerByRootThread);
        if (resolved != null) {
          total++;
        }
      }
      if (!page.hasMore()) {
        return total;
      }
    }
  }

  /** 显式根过滤值必须是 canonical 执行根：共用根解析对不存在的 Thread 抛 404，对子 Thread（自身不是链末位根）判为非法参数。 */
  private void requireCanonicalRoot(UUID rootThreadId) {
    if (!rootResolver.requireRootId(rootThreadId).equals(rootThreadId)) {
      throw new IllegalArgumentException("rootThreadId must be an execution root: " + rootThreadId);
    }
  }

  /** 沿来源不可变祖先链解析真实根、可选根过滤与产品 owner；根无产品归属时返回 null，调用方据此跳过不可暴露的行。 */
  private ResolvedRoot resolveRoot(
      HarnessRuntime runtime,
      PendingInteraction row,
      UUID filterRootThreadId,
      Map<UUID, UUID> rootBySourceThread,
      Map<UUID, Optional<InteractionOwnerDTO>> ownerByRootThread) {
    UUID rootThreadId =
        rootBySourceThread.computeIfAbsent(row.threadId(), rootResolver::requireRootId);
    if (filterRootThreadId != null && !filterRootThreadId.equals(rootThreadId)) {
      return null;
    }
    Optional<InteractionOwnerDTO> owner =
        ownerByRootThread.computeIfAbsent(rootThreadId, id -> resolveOwner(runtime, id, row));
    if (owner.isEmpty()) {
      return null;
    }
    return new ResolvedRoot(rootThreadId, owner.get());
  }

  /**
   * 产品 owner 只按根解析：根 Session 命中 {@code chat_session} 即为 Chat；根 Thread 命中 {@code
   * project_issue_agent_thread} 即为 Issue+Agent。来源自身即根时直接复用行内 Session，后代来源才回读根快照取得根 Session。
   */
  private Optional<InteractionOwnerDTO> resolveOwner(
      HarnessRuntime runtime, UUID rootThreadId, PendingInteraction row) {
    UUID rootSessionId;
    if (rootThreadId.equals(row.threadId())) {
      rootSessionId = row.sessionId();
    } else {
      rootSessionId = runtime.getThreadSnapshot(rootThreadId).thread().sessionId();
    }
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

  private static InteractionDTO toDto(PendingInteraction interaction, ResolvedRoot resolved) {
    InteractionDTO dto = new InteractionDTO();
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

  /** 已解析的来源投影：真实根与其产品 owner。 */
  private record ResolvedRoot(UUID rootThreadId, InteractionOwnerDTO owner) {}
}
