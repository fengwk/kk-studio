package fun.fengwk.kkstudio.common.page;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 分页结果值对象。
 *
 * @param <T> 结果元素类型
 */
@Getter
@EqualsAndHashCode
@ToString
public final class Page<T> {

  private final int pageNumber;
  private final int pageSize;
  private final long totalCount;
  private final List<T> results;

  public Page(int pageNumber, int pageSize, List<T> results, long totalCount) {
    if (pageNumber < 1) {
      throw new IllegalArgumentException("pageNumber must be greater than or equal to 1");
    }
    if (pageSize < 1 || pageSize > PageQuery.MAX_PAGE_SIZE) {
      throw new IllegalArgumentException(
          "pageSize must be between 1 and " + PageQuery.MAX_PAGE_SIZE + " inclusive");
    }
    if (totalCount < 0) {
      throw new IllegalArgumentException("totalCount must be greater than or equal to 0");
    }
    this.pageNumber = pageNumber;
    this.pageSize = pageSize;
    this.totalCount = totalCount;
    this.results = List.copyOf(Objects.requireNonNull(results, "results"));
  }

  /** 保持分页元数据不变，将当前结果逐项映射为目标类型。 */
  public <S> Page<S> map(Function<? super T, S> mapper) {
    Objects.requireNonNull(mapper, "mapper");
    List<S> mappedResults = results.stream().map(mapper).toList();
    return new Page<>(pageNumber, pageSize, mappedResults, totalCount);
  }
}
