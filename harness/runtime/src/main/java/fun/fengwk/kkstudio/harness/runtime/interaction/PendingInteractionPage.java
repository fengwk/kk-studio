package fun.fengwk.kkstudio.harness.runtime.interaction;

import java.util.List;
import java.util.Objects;

/**
 * 待处理 Interaction 的一页稳定分页结果。
 *
 * <p>{@code interactions} 按 {@code (createdAt, id)} 升序；{@code hasMore} 表示游标之后仍存在更多行，由 storage
 * 多取一条判定， 因此调用方据此决定是否继续翻页不会提前漏项。
 */
public record PendingInteractionPage(List<PendingInteraction> interactions, boolean hasMore) {

  public PendingInteractionPage {
    interactions = List.copyOf(Objects.requireNonNull(interactions, "interactions"));
  }
}
