package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 待处理 Interaction 的一页稳定分页结果。
 *
 * <p>{@code items} 按 {@code (createdAt, toolInvocationId)} 升序；{@code nextCursor}
 * 非空表示还有下一页，原样回传即可继续翻页。{@code total} 是同一过滤条件下的真实可见待处理总数（与分页位置无关），因此客户端不必用首页长度假装全局计数，也不需要自己维护待办台账。
 *
 * <p>{@code freshnessAt} 是本次结果仅因时间推移（没有任何写事件、因此不会有通知）最早可能改变的权威时刻：例如候选项被有效环境连接租约抑制而该租约即将到期， 或 TOOL
 * Work 尚未到期。客户端据此安排一次回读即可，不需要轮询；没有这类时刻时省略。
 */
@Data
public class InteractionPageDTO {

  private List<InteractionDTO> items = new ArrayList<>();

  private @Nullable String nextCursor;

  private int total;

  private @Nullable Instant freshnessAt;
}
