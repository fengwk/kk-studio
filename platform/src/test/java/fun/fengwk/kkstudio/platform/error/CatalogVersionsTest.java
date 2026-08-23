package fun.fengwk.kkstudio.platform.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CatalogVersionsTest {

  @Test
  void rejectsNonCanonicalValuesAndNegativeInternalVersions() {
    assertThrows(AiValidationException.class, () -> CatalogVersions.parse(" 1", "expectedVersion"));
    assertThrows(AiValidationException.class, () -> CatalogVersions.parse("1 ", "expectedVersion"));
    assertThrows(AiValidationException.class, () -> CatalogVersions.parse(" ", "expectedVersion"));
    assertThrows(IllegalStateException.class, () -> CatalogVersions.format(-1L));
  }

  @Test
  void preservesCanonicalVersions() {
    assertEquals(0L, CatalogVersions.parse("0", "expectedVersion"));
    assertEquals(42L, CatalogVersions.parse("42", "expectedVersion"));
    assertEquals("42", CatalogVersions.format(42L));
  }
}
