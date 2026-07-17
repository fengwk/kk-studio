package fun.fengwk.kkstudio.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.studio.canvas.CanvasLifecycle;
import fun.fengwk.kkstudio.studio.model.FunctionRef;
import fun.fengwk.kkstudio.studio.runtime.SystemFunctionIds;

/** Smoke coverage for pure domain value types. */
class StudioDomainSmokeTest {

  @Test
  void functionRefRejectsBlankIdentity() {
    assertThrows(IllegalArgumentException.class, () -> new FunctionRef(" ", "1"));
  }

  @Test
  void systemFunctionIdsAreStable() {
    FunctionRef ref =
        new FunctionRef(SystemFunctionIds.GENERATE_TEXT, SystemFunctionIds.VERSION_V1);
    assertEquals("system.generate-text", ref.functionId());
    assertEquals("1", ref.version());
  }

  @Test
  void canvasDocumentCarriesLifecycle() {
    CanvasDocument document =
        new CanvasDocument(1L, 1L, "demo", 1, 0L, CanvasLifecycle.ACTIVE, "{}");
    assertEquals(CanvasLifecycle.ACTIVE, document.lifecycle());
  }
}
