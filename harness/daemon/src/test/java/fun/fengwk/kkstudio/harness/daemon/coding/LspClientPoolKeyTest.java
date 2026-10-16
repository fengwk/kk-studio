package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link LspClientPool} 复用键的值语义：键按项目根与完整结构化配置比较。
 *
 * <p>通过反射比较私有 key 工厂的投影，不启动语言服务器；实际进程复用由池的并发测试覆盖。
 */
class LspClientPoolKeyTest {

  private static final Path ROOT = Path.of("/repo");

  /** 意图：同一项目根、同一份完整配置必须投影出相等的键，保证既有复用语义不被破坏。 */
  @Test
  void identicalRootAndConfigAreEqual() throws Exception {
    LspServerConfig config =
        config("fake", List.of("java", "-jar"), List.of(".java"), List.of(), List.of());
    LspServerConfig equalConfig =
        config("fake", List.of("java", "-jar"), List.of(".java"), List.of(), List.of());
    assertEquals(key(ROOT, config), key(ROOT, equalConfig));
  }

  /** 意图：命令参数按逗号/空格重新分组后 {@code List.toString()} 相同，但配置结构不同，键必须不同。 */
  @Test
  void commandsThatShareListToStringAreNotEqual() throws Exception {
    Object spread =
        key(ROOT, config("fake", List.of("java", "a, b"), List.of(".java"), List.of(), List.of()));
    Object split =
        key(
            ROOT,
            config("fake", List.of("java", "a", "b"), List.of(".java"), List.of(), List.of()));
    assertNotEquals(spread, split, "List.toString() 表示相同不代表配置相同");
  }

  /** 意图：同一份配置落在不同项目根时必须是不同的键，不得复用同一个实例。 */
  @Test
  void differentRootIsNotEqual() throws Exception {
    LspServerConfig config =
        config("fake", List.of("java", "-jar"), List.of(".java"), List.of(), List.of());
    assertNotEquals(key(ROOT, config), key(ROOT.resolve("module"), config));
  }

  /** 意图：id、扩展名与两类项目根标记都参与复用键，任一不同都必须是不同的键。 */
  @Test
  void differentConfigFieldsAreNotEqual() throws Exception {
    Object base =
        key(
            ROOT,
            config(
                "fake", List.of("java", "-jar"), List.of(".java"), List.of("pom.xml"), List.of()));
    assertNotEquals(
        base,
        key(
            ROOT,
            config(
                "other", List.of("java", "-jar"), List.of(".java"), List.of("pom.xml"), List.of())),
        "id 不同");
    assertNotEquals(
        base,
        key(
            ROOT,
            config(
                "fake", List.of("java", "-jar"), List.of(".txt"), List.of("pom.xml"), List.of())),
        "扩展名不同");
    assertNotEquals(
        base,
        key(ROOT, config("fake", List.of("java", "-jar"), List.of(".java"), List.of(), List.of())),
        "rootMarkers 不同");
    assertNotEquals(
        base,
        key(
            ROOT,
            config(
                "fake",
                List.of("java", "-jar"),
                List.of(".java"),
                List.of("pom.xml"),
                List.of(".git"))),
        "firstMatchMarkers 不同");
  }

  private static LspServerConfig config(
      String id,
      List<String> command,
      List<String> extensions,
      List<String> rootMarkers,
      List<String> firstMatchMarkers) {
    return new LspServerConfig(id, command, extensions, rootMarkers, firstMatchMarkers);
  }

  /** 反射调用私有 key 工厂；返回的是键投影，仅用于值语义比较。 */
  private static Object key(Path root, LspServerConfig config) throws Exception {
    Method method = LspClientPool.class.getDeclaredMethod("key", Path.class, LspServerConfig.class);
    method.setAccessible(true);
    return method.invoke(null, root, config);
  }
}
