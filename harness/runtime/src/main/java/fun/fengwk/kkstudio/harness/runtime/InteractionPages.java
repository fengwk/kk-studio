package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.interaction.PendingEnvironmentWait;
import fun.fengwk.kkstudio.harness.api.interaction.PendingEnvironmentWaitPage;
import fun.fengwk.kkstudio.harness.api.interaction.PendingInteraction;
import fun.fengwk.kkstudio.harness.api.interaction.PendingInteractionPage;
import fun.fengwk.kkstudio.harness.api.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.codec.ToolApprovalJsonCodec;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.store.PendingEnvironmentWaitRow;
import fun.fengwk.kkstudio.harness.spi.store.PendingToolInvocationRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 待处理 Interaction 与环境等待候选的只读分页投影。
 *
 * <p>两种查询都是 keyset 游标：游标为严格下界，首屏用 {@link Instant#EPOCH} 与全零 UUID，storage 多取一条判定 {@code
 * hasMore}，因此调用方按 {@code hasMore} 翻页不会提前漏项。这里只暴露持久事实，产品 owner、Pane 跳转与环境在线状态由上层解析。
 */
final class InteractionPages {

  private static final ToolApprovalJsonCodec APPROVAL_JSON = new ToolApprovalJsonCodec();

  private final HarnessStore store;

  InteractionPages(HarnessStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /** 一页 {@code WAITING_INPUT} / {@code WAITING_APPROVAL} 调用，按 {@code (createdAt, id)} 稳定升序。 */
  PendingInteractionPage pendingInteractions(Instant afterCreatedAt, UUID afterId, int limit) {
    Objects.requireNonNull(afterCreatedAt, "afterCreatedAt");
    Objects.requireNonNull(afterId, "afterId");
    if (limit <= 0) {
      throw DomainException.validation("limit", "limit must be positive");
    }
    int probeSize = limit == Integer.MAX_VALUE ? limit : limit + 1;
    return store.transaction(
        tx -> {
          List<PendingToolInvocationRow> rows =
              tx.tools().listPending(afterCreatedAt, afterId, probeSize);
          boolean hasMore = rows.size() > limit;
          List<PendingInteraction> interactions = new ArrayList<>(Math.min(rows.size(), limit));
          for (PendingToolInvocationRow row : rows) {
            if (interactions.size() == limit) {
              break;
            }
            interactions.add(toPendingInteraction(row));
          }
          return new PendingInteractionPage(interactions, hasMore);
        });
  }

  private static PendingInteraction toPendingInteraction(PendingToolInvocationRow row) {
    ToolInvocation invocation = row.invocation();
    ToolApproval approval = invocation.approval();
    String approvalJson = approval == null ? null : APPROVAL_JSON.encode(approval);
    return new PendingInteraction(
        invocation.id(),
        row.threadId(),
        row.sessionId(),
        invocation.status(),
        invocation.call().id(),
        invocation.call().toolName(),
        invocation.call().argumentsJson(),
        approvalJson,
        invocation.createdAt());
  }

  /**
   * READY TOOL 调用及到期且无有效执行 lease 的 TOOL Work 候选：先按根和环境分组，再按代表键分页。
   *
   * <p>生产实现的 due / lease 判定使用数据库时钟。
   */
  PendingEnvironmentWaitPage environmentWaitCandidates(
      Instant afterRepresentativeCreatedAt, UUID afterRepresentativeInvocationId, int limit) {
    Objects.requireNonNull(afterRepresentativeCreatedAt, "afterRepresentativeCreatedAt");
    Objects.requireNonNull(afterRepresentativeInvocationId, "afterRepresentativeInvocationId");
    if (limit <= 0) {
      throw DomainException.validation("limit", "limit must be positive");
    }
    int probeSize = limit == Integer.MAX_VALUE ? limit : limit + 1;
    return store.transaction(
        tx -> {
          List<PendingEnvironmentWaitRow> rows =
              tx.tools()
                  .listEnvironmentWaitCandidates(
                      afterRepresentativeCreatedAt, afterRepresentativeInvocationId, probeSize);
          boolean hasMore = rows.size() > limit;
          List<PendingEnvironmentWait> waits = new ArrayList<>(Math.min(rows.size(), limit));
          for (PendingEnvironmentWaitRow row : rows) {
            if (waits.size() == limit) {
              break;
            }
            waits.add(toPendingEnvironmentWait(row));
          }
          return new PendingEnvironmentWaitPage(waits, hasMore);
        });
  }

  private static PendingEnvironmentWait toPendingEnvironmentWait(PendingEnvironmentWaitRow row) {
    return new PendingEnvironmentWait(
        row.rootThreadId(),
        row.environmentId(),
        row.representativeCreatedAt(),
        row.representativeInvocationId(),
        row.waitingCount());
  }
}
