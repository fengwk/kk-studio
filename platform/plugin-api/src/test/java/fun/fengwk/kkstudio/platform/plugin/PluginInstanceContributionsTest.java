package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link PluginInstance#contributionsOf(Class, List)} 的类型读取契约：按类型筛选、保持冻结顺序、未知类型为空，读取结果不可变。
 *
 * <p>两个示例类型模拟不同产品域的能力：实例只冻结一份产品域贡献，读取方按自己拥有的类型取用。
 */
class PluginInstanceContributionsTest {

  private interface Tool {}

  private interface Function {}

  private record NamedTool(String name) implements Tool {}

  private record NamedFunction(String name) implements Function {}

  @Test
  void readsOnlyTheRequestedTypeInFrozenOrder() {
    NamedTool first = new NamedTool("first");
    NamedFunction function = new NamedFunction("render");
    NamedTool second = new NamedTool("second");
    List<Object> frozen = List.of(first, function, second);

    assertEquals(List.of(first, second), PluginInstance.contributionsOf(Tool.class, frozen));
    assertEquals(List.of(function), PluginInstance.contributionsOf(Function.class, frozen));
    assertEquals(List.of(), PluginInstance.contributionsOf(String.class, frozen));
    assertEquals(List.of(), PluginInstance.contributionsOf(Tool.class, List.of()));
  }

  @Test
  void readResultIsImmutable() {
    List<Tool> tools = PluginInstance.contributionsOf(Tool.class, List.of(new NamedTool("first")));

    assertThrows(UnsupportedOperationException.class, () -> tools.add(new NamedTool("second")));
  }

  @Test
  void requiresTypeAndFrozenList() {
    assertThrows(NullPointerException.class, () -> PluginInstance.contributionsOf(null, List.of()));
    assertThrows(
        NullPointerException.class, () -> PluginInstance.contributionsOf(Tool.class, null));
  }
}
