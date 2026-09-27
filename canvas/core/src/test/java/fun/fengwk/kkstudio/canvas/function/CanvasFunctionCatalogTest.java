package fun.fengwk.kkstudio.canvas.function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Catalog 在 Core 内冻结函数定义、adapter 与 availability，不依赖 Spring 或其它模块。 */
class CanvasFunctionCatalogTest {

  /** 每个 adapter 的 function 与 availability 只采样一次，并在来源集合变化后保持排序与快照不变。 */
  @Test
  void samplesEachAdapterOnceAndFreezesFactsAndOrder() {
    CanvasFunctionDefinition zFunction = function("z.fn");
    CanvasFunctionDefinition aFunction = function("a.fn");
    List<CanvasFunctionDefinition> availableFunctions = new ArrayList<>(List.of(zFunction));
    List<CanvasFunctionDefinition> disabledFunctions = new ArrayList<>(List.of(aFunction));
    CountingAdapter available = new CountingAdapter(availableFunctions, true, null);
    CountingAdapter disabled = new CountingAdapter(disabledFunctions, false, "disabled");

    CanvasFunctionCatalog catalog = CanvasFunctionCatalog.from(List.of(available, disabled));
    availableFunctions.clear();
    disabledFunctions.clear();

    assertEquals(List.of("a.fn", "z.fn"), names(catalog));
    assertEquals(
        List.of("a.fn", "z.fn"),
        names(
            CanvasFunctionCatalog.from(
                List.of(
                    new CountingAdapter(List.of(zFunction), true, null),
                    new CountingAdapter(List.of(aFunction), false, "disabled")))));
    assertEquals(1, available.functionsCalls);
    assertEquals(1, available.enabledCalls);
    assertEquals(1, available.reasonCalls);
    assertEquals(1, disabled.functionsCalls);
    assertEquals(1, disabled.enabledCalls);
    assertEquals(1, disabled.reasonCalls);

    CanvasFunctionCatalog.RegisteredFunction availableEntry = catalog.require("z.fn");
    CanvasFunctionCatalog.RegisteredFunction disabledEntry = catalog.require("a.fn");
    assertSame(available, availableEntry.adapter());
    assertSame(disabled, disabledEntry.adapter());
    assertTrue(availableEntry.enabled());
    assertNull(availableEntry.unavailableReason());
    assertFalse(disabledEntry.enabled());
    assertEquals("disabled", disabledEntry.unavailableReason());
    assertDoesNotThrow(availableEntry::requireAvailable);
    IllegalArgumentException unavailable =
        assertThrows(IllegalArgumentException.class, disabledEntry::requireAvailable);
    assertEquals("Canvas Function is unavailable: disabled", unavailable.getMessage());
    assertThrows(UnsupportedOperationException.class, () -> catalog.list().clear());
  }

  /** Catalog 必须在构造期拒绝空输入项、空 function 声明、空 function 和重复函数名。 */
  @Test
  void rejectsNullsEmptyFunctionsDuplicatesAndUnknownNames() {
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
                    new CountingAdapter(List.of(function("same")), true, null),
                    new CountingAdapter(List.of(function("same")), true, null))));

    CanvasFunctionCatalog catalog =
        CanvasFunctionCatalog.from(
            List.of(new CountingAdapter(List.of(function("known")), true, null)));
    IllegalArgumentException unknown =
        assertThrows(IllegalArgumentException.class, () -> catalog.require("unknown"));
    assertEquals("unknown Canvas Function: unknown", unknown.getMessage());
  }

  /** 没有 adapter 时仍返回稳定的空 Catalog，避免把「单个 adapter 不得空 function」误作全局非空约束。 */
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
                List.of(new CountingAdapter(List.of(function("enabled")), true, "reason"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionCatalog.from(
                List.of(new CountingAdapter(List.of(function("disabled-null")), false, null))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CanvasFunctionCatalog.from(
                List.of(new CountingAdapter(List.of(function("disabled-blank")), false, "  "))));
  }

  private static List<String> names(CanvasFunctionCatalog catalog) {
    return catalog.list().stream().map(entry -> entry.function().name()).toList();
  }

  static CanvasFunctionDefinition function(String name) {
    return new CanvasFunctionDefinition(
        name,
        name,
        CanvasJson.parseObject(
            "{\"type\":\"object\",\"properties\":{},\"required\":[],"
                + "\"additionalProperties\":false}"),
        List.of(CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE)),
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  }

  private static final class CountingAdapter implements CanvasFunctionAdapter {

    private final List<CanvasFunctionDefinition> functions;
    private boolean enabled;
    private String unavailableReason;
    private int functionsCalls;
    private int enabledCalls;
    private int reasonCalls;

    private CountingAdapter(
        List<CanvasFunctionDefinition> functions, boolean enabled, String unavailableReason) {
      this.functions = functions;
      this.enabled = enabled;
      this.unavailableReason = unavailableReason;
    }

    @Override
    public List<CanvasFunctionDefinition> functions() {
      functionsCalls++;
      return functions;
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
    public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> execute(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
      throw new UnsupportedOperationException();
    }
  }
}
