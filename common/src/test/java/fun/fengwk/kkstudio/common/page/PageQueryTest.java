package fun.fengwk.kkstudio.common.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PageQueryTest {

  @Test
  void exposesBoundedPageAndSafeOffset() {
    PageQuery query = new PageQuery(3, 20);

    assertEquals(3, query.getPageNumber());
    assertEquals(20, query.getPageSize());
    assertEquals(40L, query.getOffset());
    assertEquals(PageQuery.MAX_PAGE_SIZE, 1000);
  }

  @Test
  void largePageNumberDoesNotOverflowOffset() {
    // 32 位乘法会在此溢出为负数；值对象必须以 long 计算。
    PageQuery query = new PageQuery(Integer.MAX_VALUE, PageQuery.MAX_PAGE_SIZE);

    assertEquals((Integer.MAX_VALUE - 1L) * PageQuery.MAX_PAGE_SIZE, query.getOffset());
  }

  @Test
  void rejectsOutOfRangePageInput() {
    assertThrows(IllegalArgumentException.class, () -> new PageQuery(0, 50));
    assertThrows(IllegalArgumentException.class, () -> new PageQuery(-1, 50));
    assertThrows(IllegalArgumentException.class, () -> new PageQuery(1, 0));
    assertThrows(IllegalArgumentException.class, () -> new PageQuery(1, -1));
    assertThrows(
        IllegalArgumentException.class, () -> new PageQuery(1, PageQuery.MAX_PAGE_SIZE + 1));
  }
}
