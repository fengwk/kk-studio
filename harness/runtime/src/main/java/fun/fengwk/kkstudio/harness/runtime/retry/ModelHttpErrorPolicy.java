package fun.fengwk.kkstudio.harness.runtime.retry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 模型 HTTP 失败的自动重试白名单策略。
 *
 * <p>只承载 400–599 的 HTTP error 状态：状态命中 retry 集合时允许进入既有重试预算，未列出即直接失败。该策略只决定 retry
 * eligibility，不是第二个重试预算；实际延迟与次数仍由 {@link InvocationRetryPolicy} 唯一决策。
 *
 * <p>OVERFLOW 与 CANCELLED 等专用语义、以及无 HTTP 的网络 / 破损响应不经过本策略。默认白名单为 {@code [408, 429, 500, 502, 503,
 * 504]}，与系统设置默认聚合及初始化 SQL 保持一致。
 */
public record ModelHttpErrorPolicy(List<Integer> retryStatusCodes) {

  /** 系统默认白名单；同时是系统设置与初始化 SQL 的默认值来源。 */
  public static final List<Integer> DEFAULT_RETRY_STATUS_CODES =
      List.of(408, 429, 500, 502, 503, 504);

  /** 默认策略：仅重试 {@link #DEFAULT_RETRY_STATUS_CODES}。 */
  public static final ModelHttpErrorPolicy DEFAULT =
      new ModelHttpErrorPolicy(DEFAULT_RETRY_STATUS_CODES);

  public ModelHttpErrorPolicy {
    retryStatusCodes = requireStatusCodes(retryStatusCodes, "retryStatusCodes");
  }

  /** 状态命中白名单即允许重试；未列出直接失败。 */
  public boolean allowsRetry(int statusCode) {
    return retryStatusCodes.contains(statusCode);
  }

  /** 仅接受 400–599 的整型状态；拒绝 null 元素、重复与越界，空列表合法（表示不自动重试任何 HTTP 错误）。 */
  private static List<Integer> requireStatusCodes(List<Integer> source, String name) {
    Objects.requireNonNull(source, name);
    List<Integer> copy = new ArrayList<>(source.size());
    for (Integer status : source) {
      if (status == null || status < 400 || status > 599) {
        throw new IllegalArgumentException(name + " must contain only HTTP error statuses 400-599");
      }
      if (copy.contains(status)) {
        throw new IllegalArgumentException(name + " must not contain duplicate statuses");
      }
      copy.add(status);
    }
    return List.copyOf(copy);
  }
}
