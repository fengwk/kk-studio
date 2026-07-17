package fun.fengwk.kkstudio.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.studio.service.InMemoryFunctionCatalog;
import fun.fengwk.kkstudio.studio.model.FunctionRef;
import fun.fengwk.kkstudio.studio.runtime.SystemFunctionIds;

/** Ensures bootstrap catalog exposes planned system Functions as stubs. */
class InMemoryFunctionCatalogTest {

  @Test
  void listsSystemGenerationFunctions() {
    InMemoryFunctionCatalog catalog = new InMemoryFunctionCatalog();
    assertEquals(4, catalog.listVisible(1L).size());
    assertTrue(
        catalog
            .find(new FunctionRef(SystemFunctionIds.GENERATE_IMAGE, SystemFunctionIds.VERSION_V1))
            .isPresent());
  }
}
