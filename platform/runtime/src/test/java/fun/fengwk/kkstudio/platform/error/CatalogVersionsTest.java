package fun.fengwk.kkstudio.platform.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;

class CatalogVersionsTest {

  @Test
  void rejectsNonCanonicalValuesAndNegativeInternalVersions() {
    // 测试意图：领域异常直接转发共同解析器的真实文案。
    DomainException padded =
        assertThrows(DomainException.class, () -> CatalogVersions.parse(" 1", "expectedVersion"));
    assertEquals(
        "expectedVersion must be a canonical non-negative decimal:  1", padded.getMessage());
    assertInstanceOf(IllegalArgumentException.class, padded.getCause());
    assertThrows(DomainException.class, () -> CatalogVersions.parse(null, "expectedVersion"));
    assertThrows(IllegalStateException.class, () -> CatalogVersions.format(-1L));
  }

  @Test
  void preservesCanonicalVersions() {
    assertEquals(0L, CatalogVersions.parse("0", "expectedVersion"));
    assertEquals(42L, CatalogVersions.parse("42", "expectedVersion"));
    assertEquals("42", CatalogVersions.format(42L));
  }
}
