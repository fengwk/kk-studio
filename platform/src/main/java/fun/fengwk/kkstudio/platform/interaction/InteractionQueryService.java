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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 统一待处理交互查询用例。
 *
 * <p>事实源只有 Harness 两种等待状态：{@code WAITING_INPUT} 与 {@code WAITING_APPROVAL} 的
 * Invocation，不复制待办、不按状态拆成 两次查询。产品 owner 由服务端直接解析：Session 命中 {@code chat_session} 即为 Chat；Thread 命中
 * {@code project_issue_agent_thread} 即为 Issue+Agent。两者都无法解析的 Thread（内部委派，例如隐藏的 {@code ask_user}
 * 子线程）不对外暴露。当前沿用单用户认证边界，不伪造用户表，也不再接受客户端传入 owner/身份。
 *
 * <p>分页按 {@code (createdAt, id)} keyset 稳定升序。因为待处理行需要先按产品归属过滤再对外暴露，本用例必须持续向后扫描：每轮按剩余额度取原始行，用
 * 本页最后一行推进游标，直到凑满 {@code limit} 条可见项或源已耗尽，绝不因为被过滤项而提前截断返回。
 */
@Service
public class InteractionQueryService {

  private final ChatSessionRepository chatSessionRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final ObjectProvider<HarnessRuntime> runtimes;

  public InteractionQueryService(
      ChatSessionRepository chatSessionRepository,
      IssueAgentThreadRepository issueAgentThreadRepository,
      ObjectProvider<HarnessRuntime> runtimes) {
    this.chatSessionRepository =
        Objects.requireNonNull(chatSessionRepository, "chatSessionRepository");
    this.issueAgentThreadRepository =
        Objects.requireNonNull(issueAgentThreadRepository, "issueAgentThreadRepository");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
  }

  /**
   * 返回一页待处理 Interaction；{@code cursor} 为空表示首屏，{@code limit} 必须为正（调用方已在边界校验上限）。
   *
   * <p>{@code nextCursor} 只定位本页最后一条「已消费的原始行」，因此即使整页都被归属过滤后为空，客户端回传同一游标也能继续向前翻页而不 漏项、不空转；源耗尽时返回
   * {@code null}。
   */
  public InteractionPageDTO listInteractions(String cursor, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    HarnessRuntime runtime = requireRuntime();
    InteractionCursor decoded = InteractionCursor.parse(cursor);
    Instant afterCreatedAt = decoded.createdAt();
    UUID afterId = decoded.id();
    List<InteractionDTO> items = new ArrayList<>(limit);
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
        InteractionOwnerDTO owner = resolveOwner(row);
        if (owner == null) {
          continue;
        }
        items.add(toDto(row, owner));
      }
      if (!page.hasMore()) {
        exhausted = true;
        break;
      }
    }
    InteractionPageDTO dto = new InteractionPageDTO();
    dto.setItems(items);
    dto.setNextCursor(exhausted ? null : InteractionCursor.encode(afterCreatedAt, afterId));
    return dto;
  }

  /** 产品 owner 只按既有归属边解析；两者都缺失时返回 null，调用方据此跳过内部委派等不可暴露的 Thread。 */
  private InteractionOwnerDTO resolveOwner(PendingInteraction interaction) {
    ChatSession chatSession = chatSessionRepository.findBySessionId(interaction.sessionId());
    if (chatSession != null) {
      InteractionOwnerDTO owner = new InteractionOwnerDTO();
      owner.setType("CHAT");
      owner.setChatId(chatSession.chatId().toString());
      return owner;
    }
    IssueAgentThread binding = issueAgentThreadRepository.findByThreadId(interaction.threadId());
    if (binding != null) {
      InteractionOwnerDTO owner = new InteractionOwnerDTO();
      owner.setType("ISSUE_AGENT");
      owner.setIssueId(binding.issueId().toString());
      owner.setAgentName(binding.agentName());
      return owner;
    }
    return null;
  }

  private static InteractionDTO toDto(PendingInteraction interaction, InteractionOwnerDTO owner) {
    InteractionDTO dto = new InteractionDTO();
    dto.setInteractionId(interaction.invocationId().toString());
    dto.setStatus(interaction.status().name());
    dto.setThreadId(interaction.threadId().toString());
    dto.setSessionId(interaction.sessionId().toString());
    dto.setOwner(owner);
    dto.setToolCallId(interaction.toolCallId());
    dto.setToolName(interaction.toolName());
    dto.setArgumentsJson(interaction.argumentsJson());
    dto.setApprovalJson(interaction.approvalJson());
    dto.setCreateTime(interaction.createdAt());
    return dto;
  }

  private HarnessRuntime requireRuntime() {
    HarnessRuntime runtime = runtimes.getIfAvailable();
    if (runtime == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return runtime;
  }
}
