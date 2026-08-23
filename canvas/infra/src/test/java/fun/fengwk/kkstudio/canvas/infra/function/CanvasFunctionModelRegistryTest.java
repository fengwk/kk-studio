package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Registry key 唯一、稳定排序与 adapter availability。 */
class CanvasFunctionModelRegistryTest {

  @Test
  void sortsModelsAndKeepsUnavailableAdapterFacts() {
    CanvasFunctionModelRegistry registry =
        new CanvasFunctionModelRegistry(
            List.of(
                adapter(model("z-model"), true, null),
                adapter(model("a-model"), false, "disabled")));

    assertEquals(
        List.of("a-model", "z-model"),
        registry.list().stream().map(value -> value.model().key()).toList());
    assertFalse(registry.list().get(0).adapter().enabled());
    assertEquals("disabled", registry.list().get(0).adapter().unavailableReason());
  }

  @Test
  void rejectsDuplicateModelKeysAtConstruction() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CanvasFunctionModelRegistry(
                List.of(adapter(model("same"), true, null), adapter(model("same"), true, null))));
  }

  /** Adapter availability facts 必须自洽，空 model 与矛盾 reason 在启动时 fail-fast。 */
  @Test
  void rejectsInvalidAdapterAvailabilityDeclarations() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasFunctionModelRegistry(List.of(adapter(model("enabled"), true, "reason"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasFunctionModelRegistry(List.of(adapter(model("disabled"), false, null))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CanvasFunctionModelRegistry(List.of(emptyAdapter())));
    CanvasFunctionModelRegistry registry =
        new CanvasFunctionModelRegistry(List.of(adapter(model("known"), true, null)));
    assertThrows(IllegalArgumentException.class, () -> registry.require("unknown"));
  }

  private static CanvasFunctionModel model(String key) {
    return new CanvasFunctionModel(
        key,
        key,
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
        List.of());
  }

  private static CanvasFunctionAdapter adapter(
      CanvasFunctionModel model, boolean enabled, String reason) {
    return new CanvasFunctionAdapter() {
      @Override
      public List<CanvasFunctionModel> models() {
        return List.of(model);
      }

      @Override
      public boolean enabled() {
        return enabled;
      }

      @Override
      public String unavailableReason() {
        return reason;
      }

      @Override
      public void preflight(CanvasFunctionFrozenRun run) {}

      @Override
      public List<UUID> execute(
          CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static CanvasFunctionAdapter emptyAdapter() {
    return new CanvasFunctionAdapter() {
      @Override
      public List<CanvasFunctionModel> models() {
        return List.of();
      }

      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public String unavailableReason() {
        return null;
      }

      @Override
      public void preflight(CanvasFunctionFrozenRun run) {}

      @Override
      public List<UUID> execute(
          CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
