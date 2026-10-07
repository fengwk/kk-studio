package fun.fengwk.kkstudio.harness.runtime.interaction;

import java.util.List;
import java.util.Objects;

/**
 * 环境等待条目的一页稳定分页结果。
 *
 * <p>{@code waits} 按分组代表的 {@code (representativeCreatedAt, representativeInvocationId)} 升序；{@code
 * hasMore} 表示游标之后仍存在更多分组，由 storage 多取一条判定，因此调用方据此翻页不会提前漏项。
 */
public record PendingEnvironmentWaitPage(List<PendingEnvironmentWait> waits, boolean hasMore) {

  public PendingEnvironmentWaitPage {
    waits = List.copyOf(Objects.requireNonNull(waits, "waits"));
  }
}
