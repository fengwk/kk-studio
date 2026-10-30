package fun.fengwk.kkstudio.common.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

class PageTest {

  @Test
  void preservesMetadataAndMapsResults() {
    Page<Integer> page = new Page<>(2, 10, List.of(1, 2, 3), 23L);

    assertEquals(2, page.getPageNumber());
    assertEquals(10, page.getPageSize());
    assertEquals(23L, page.getTotalCount());
    assertEquals(List.of(1, 2, 3), page.getResults());

    Page<String> mapped = page.map(String::valueOf);
    assertEquals(2, mapped.getPageNumber());
    assertEquals(10, mapped.getPageSize());
    assertEquals(23L, mapped.getTotalCount());
    assertEquals(List.of("1", "2", "3"), mapped.getResults());
  }

  @Test
  void rejectsIncompletePageMetadata() {
    assertThrows(IllegalArgumentException.class, () -> new Page<>(0, 10, List.of(), 0));
    assertThrows(IllegalArgumentException.class, () -> new Page<>(1, 0, List.of(), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Page<>(1, PageQuery.MAX_PAGE_SIZE + 1, List.of(), 0));
    assertThrows(IllegalArgumentException.class, () -> new Page<>(1, 10, List.of(), -1));
    assertThrows(NullPointerException.class, () -> new Page<>(1, 10, null, 0));
  }
}
