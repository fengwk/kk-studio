package fun.fengwk.kkstudio.common.page;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * 有界分页查询值对象。
 *
 * <p>页码从 1 开始，页大小限制在 {@link #MAX_PAGE_SIZE} 以内。{@link #getOffset()} 使用 {@code long} 计算并等价于 {@code
 * (pageNumber - 1) * pageSize}，避免大页码下的整型乘法溢出。
 */
@Getter
@EqualsAndHashCode
@ToString
public final class PageQuery {

  /** 单页最大条数；与既有目录分页契约保持一致。 */
  public static final int MAX_PAGE_SIZE = 1000;

  private final int pageNumber;
  private final int pageSize;

  public PageQuery(int pageNumber, int pageSize) {
    if (pageNumber < 1) {
      throw new IllegalArgumentException("pageNumber must be greater than or equal to 1");
    }
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and " + MAX_PAGE_SIZE + " inclusive");
    }
    this.pageNumber = pageNumber;
    this.pageSize = pageSize;
  }

  /** 返回可直接用于 SQL offset 的非负值。 */
  public long getOffset() {
    return Math.multiplyExact((long) (pageNumber - 1), pageSize);
  }
}
