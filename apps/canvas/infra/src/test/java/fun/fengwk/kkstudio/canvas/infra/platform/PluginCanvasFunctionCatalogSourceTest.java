package fun.fengwk.kkstudio.canvas.infra.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasJson;
import fun.fengwk.kkstudio.canvas.function.CanvasResourceKind;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link PluginCanvasFunctionCatalogSource} 钉住当前快照，并只取回定义完全一致的函数。 */
class PluginCanvasFunctionCatalogSourceTest {

  private static final CanvasFunctionDefinition DEFINITION = definition("Render");

  private final PluginCatalogRuntime runtime = mock(PluginCatalogRuntime.class);
  private final PluginCatalogRuntime.Lease snapshotLease = mock(PluginCatalogRuntime.Lease.class);
  private CanvasFunctionCatalog catalog;

  @BeforeEach
  void setUp() {
    CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    when(adapter.functions()).thenReturn(List.of(DEFINITION));
    when(adapter.enabled()).thenReturn(true);
    catalog = CanvasFunctionCatalog.from(List.of(adapter));
    PluginCatalogSnapshot snapshot = mock(PluginCatalogSnapshot.class);
    when(snapshot.canvasFunctionCatalog()).thenReturn(catalog);
    when(snapshotLease.snapshot()).thenReturn(snapshot);
    when(runtime.acquire()).thenReturn(snapshotLease);
  }

  /** bind 冻结当前完整定义，require 按同一定义取回注册项。 */
  @Test
  void bindsAndRequiresTheCurrentDefinition() {
    try (CanvasFunctionCatalogSource.Lease lease =
        new PluginCanvasFunctionCatalogSource(runtime).acquire()) {
      assertSame(catalog, lease.catalog());
      CanvasFunctionBinding binding = lease.bind("test.render");
      assertEquals(DEFINITION, binding.definition());
      assertSame(catalog.require("test.render"), lease.require(binding));
    }
  }

  /** 函数已移除或定义变化时拒绝，绝不退回当前同名函数。 */
  @Test
  void rejectsRemovedOrChangedFunctions() {
    try (CanvasFunctionCatalogSource.Lease lease =
        new PluginCanvasFunctionCatalogSource(runtime).acquire()) {
      assertThrows(
          IllegalStateException.class,
          () -> lease.require(new CanvasFunctionBinding(definition("Changed"))));
      assertThrows(
          IllegalStateException.class,
          () ->
              lease.require(
                  new CanvasFunctionBinding(
                      CanvasFunctionDefinition.of(
                          "test.removed",
                          "Removed",
                          DEFINITION.argsSchema(),
                          CanvasResourceKind.IMAGE,
                          DEFINITION.referencePolicy()))));
    }
  }

  /** 关闭幂等且只释放一次快照租用；关闭后拒绝继续使用。 */
  @Test
  void closeReleasesTheSnapshotOnce() {
    CanvasFunctionCatalogSource.Lease lease =
        new PluginCanvasFunctionCatalogSource(runtime).acquire();
    lease.close();
    lease.close();

    verify(snapshotLease, times(1)).close();
    assertThrows(IllegalStateException.class, lease::catalog);
  }

  private static CanvasFunctionDefinition definition(String description) {
    return CanvasFunctionDefinition.of(
        "test.render",
        description,
        CanvasJson.parseObject(
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  }
}
