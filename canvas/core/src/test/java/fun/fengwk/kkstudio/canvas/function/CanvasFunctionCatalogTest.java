package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Catalog 在 Core 内冻结 model、adapter 与 availability，不依赖 Spring 或其它模块。 */
class CanvasFunctionCatalogTest {

  /** 每个 adapter 的 model 与 availability 只采样一次，并在来源集合变化后保持排序与快照不变。 */
  @Test
  void samplesEachAdapterOnceAndFreezesFactsAndOrder() {
    CanvasFunctionModel zModel = model("z-model");
    CanvasFunctionModel aModel = model("a-model");
    List<CanvasFunctionModel> availableModels = new ArrayList<>(List.of(zModel));
    List<CanvasFunctionModel> disabledModels = new ArrayList<>(List.of(aModel));
    CountingAdapter available = new CountingAdapter(availableModels, true, null);
    CountingAdapter disabled = new CountingAdapter(disabledModels, false, "disabled");

    CanvasFunctionCatalog catalog = CanvasFunctionCatalog.from(List.of(available, disabled));
    availableModels.clear();
    disabledModels.clear();

    assertEquals(List.of("a-model", "z-model"), keys(catalog));
    assertEquals(
        List.of("a-model", "z-model"),
        keys(
            CanvasFunctionCatalog.from(
                List.of(
                    new CountingAdapter(List.of(zModel), true, null),
                    new CountingAdapter(List.of(aModel), false, "disabled")))));
    assertEquals(1, available.modelsCalls);
    assertEquals(1, available.enabledCalls);
    assertEquals(1, available.reasonCalls);
    assertEquals(1, disabled.modelsCalls);
    assertEquals(1, disabled.enabledCalls);
    assertEquals(1, disabled.reasonCalls);

    CanvasFunctionCatalog.RegisteredModel availableEntry = catalog.require("z-model");
    CanvasFunctionCatalog.RegisteredModel disabledEntry = catalog.require("a-model");
    assertSame(available, availableEntry.adapter());
    assertSame(disabled, disabledEntry.adapter());
    assertTrue(availableEntry.enabled());
    assertNull(availableEntry.unavailableReason());
    assertFalse(disabledEntry.enabled());
    assertEquals("disabled", disabledEntry.unavailableReason());
    assertDoesNotThrow(availableEntry::requireAvailable);
    IllegalArgumentException unavailable =
        assertThrows(IllegalArgumentException.class, disabledEntry::requireAvailable);
    assertEquals("Canvas Function model is unavailable: disabled", unavailable.getMessage());
    assertThrows(UnsupportedOperationException.class, () -> catalog.list().clear());
  }

  /** Catalog 必须在构造期拒绝空输入项、空 model 声明、空 model 和重复 key。 */
  @Test
  void rejectsNullsEmptyModelsDuplicatesAndUnknownKeys() {
    assertThrows(NullPointerException.class, () -> CanvasFunctionCatalog.from(null));
    assertThrows(
        NullPointerException.class,
        () -> CanvasFunctionCatalog.from(Collections.singletonList(null)));
    assertThrows(
        NullPointerException.class,
        () -> CanvasFunctionCatalog.from(List.of(new CountingAdapter(null, true, null))));
    assertThrows(
        NullPointerException.class,
        () ->
            CanvasFunctionCatalog.from(
                List.of(new CountingAdapter(Collections.singletonList(null), true, null))));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionCatalog.from(List.of(new CountingAdapter(List.of(), true, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionCatalog.from(
                List.of(
                    new CountingAdapter(List.of(model("same")), true, null),
                    new CountingAdapter(List.of(model("same")), true, null))));

    CanvasFunctionCatalog catalog =
        CanvasFunctionCatalog.from(
            List.of(new CountingAdapter(List.of(model("known")), true, null)));
    IllegalArgumentException unknown =
        assertThrows(IllegalArgumentException.class, () -> catalog.require("unknown"));
    assertEquals("unknown Canvas Function model: unknown", unknown.getMessage());
  }

  /** 没有 adapter 时仍返回稳定的空 Catalog，避免把“单个 adapter 不得空 model”误作全局非空约束。 */
  @Test
  void acceptsAnEmptyAdapterCollection() {
    assertTrue(CanvasFunctionCatalog.from(List.of()).list().isEmpty());
  }

  /** availability 声明必须自洽，disabled 必须提供非 blank 的原因。 */
  @Test
  void rejectsInconsistentAvailabilityDeclarationsIncludingBlankReason() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionCatalog.from(
                List.of(new CountingAdapter(List.of(model("enabled")), true, "reason"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionCatalog.from(
                List.of(new CountingAdapter(List.of(model("disabled-null")), false, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionCatalog.from(
                List.of(new CountingAdapter(List.of(model("disabled-blank")), false, "  "))));
  }

  private static List<String> keys(CanvasFunctionCatalog catalog) {
    return catalog.list().stream().map(entry -> entry.model().key()).toList();
  }

  private static CanvasFunctionModel model(String key) {
    return new CanvasFunctionModel(
        key,
        key,
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
        List.of());
  }

  private static final class CountingAdapter implements CanvasFunctionAdapter {

    private final List<CanvasFunctionModel> models;
    private boolean enabled;
    private String unavailableReason;
    private int modelsCalls;
    private int enabledCalls;
    private int reasonCalls;

    private CountingAdapter(
        List<CanvasFunctionModel> models, boolean enabled, String unavailableReason) {
      this.models = models;
      this.enabled = enabled;
      this.unavailableReason = unavailableReason;
    }

    @Override
    public List<CanvasFunctionModel> models() {
      modelsCalls++;
      return models;
    }

    @Override
    public boolean enabled() {
      enabledCalls++;
      boolean sampled = enabled;
      enabled = !enabled;
      return sampled;
    }

    @Override
    public String unavailableReason() {
      reasonCalls++;
      String sampled = unavailableReason;
      unavailableReason = sampled == null ? "drifted" : null;
      return sampled;
    }

    @Override
    public void preflight(CanvasFunctionFrozenRun run) {}

    @Override
    public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
      throw new UnsupportedOperationException();
    }
  }
}
