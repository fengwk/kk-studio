package fun.fengwk.kkstudio.harness.common.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class PromptTemplateTest {

  private static final String GREETING = "fun/fengwk/kkstudio/harness/common/prompt/greeting.txt";
  private static final String UNTERMINATED =
      "fun/fengwk/kkstudio/harness/common/prompt/unterminated.txt";
  private static final String INVALID_NAME =
      "fun/fengwk/kkstudio/harness/common/prompt/invalid-name.txt";

  private final PromptTemplateLoader loader = new PromptTemplateLoader();

  /** 验证正常从 classpath 读取并严格按变量映射进行占位符替换与渲染。 */
  @Test
  void loadsAndRendersWithExactVariables() {
    PromptTemplate template = loader.load(GREETING);
    assertEquals("Hello ${name}, your task is ${task}.\n", template.raw());
    assertEquals(List.of("name", "task"), template.variables());
    assertEquals(
        "Hello Alice, your task is write tests.\n",
        template.render(Map.of("name", "Alice", "task", "write tests")));
  }

  /** 验证同一个 Loader 实例对同一资源路径存在内存缓存，且不同 Loader 实例互不影响。 */
  @Test
  void cachesRawResourcesAcrossLoads() {
    assertSame(loader.load(GREETING), loader.load(GREETING));
    assertNotSame(loader.load(GREETING), new PromptTemplateLoader().load(GREETING));
  }

  /** 验证加载不存在或非法的资源路径时立即抛出 IllegalArgumentException。 */
  @Test
  void rejectsMissingResource() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> loader.load("fun/fengwk/kkstudio/harness/common/prompt/missing.txt"));
    assertTrue(error.getMessage().contains("missing classpath prompt template resource"));
    assertThrows(IllegalArgumentException.class, () -> loader.load(""));
    assertThrows(NullPointerException.class, () -> loader.load(null));
  }

  /** 验证模板中存在未闭合的占位符或非法字符的变量名时，在加载期解析直接拒绝。 */
  @Test
  void rejectsUnterminatedAndInvalidPlaceholdersAtLoadTime() {
    IllegalArgumentException unterminated =
        assertThrows(IllegalArgumentException.class, () -> loader.load(UNTERMINATED));
    assertTrue(unterminated.getMessage().contains("unterminated placeholder"));
    IllegalArgumentException invalid =
        assertThrows(IllegalArgumentException.class, () -> loader.load(INVALID_NAME));
    assertTrue(invalid.getMessage().contains("invalid placeholder variable name"));
  }

  /** 验证渲染时缺少声明变量（unresolved）或传入了额外变量（unexpected）时均严格拒绝。 */
  @Test
  void renderRejectsUnresolvedAndUnexpectedVariables() {
    PromptTemplate template = loader.load(GREETING);
    IllegalArgumentException unresolved =
        assertThrows(
            IllegalArgumentException.class, () -> template.render(Map.of("name", "Alice")));
    assertTrue(unresolved.getMessage().contains("unresolved placeholder ${task}"));
    IllegalArgumentException unexpected =
        assertThrows(
            IllegalArgumentException.class,
            () -> template.render(Map.of("name", "Alice", "task", "t", "extra", "x")));
    assertTrue(unexpected.getMessage().contains("unexpected variable extra"));
    Map<String, String> withNullTask = new HashMap<>();
    withNullTask.put("name", "Alice");
    withNullTask.put("task", null);
    assertThrows(IllegalArgumentException.class, () -> template.render(withNullTask));
    assertThrows(NullPointerException.class, () -> template.render(null));
  }

  /** 验证模板中重复出现的相同占位符变量被正确去重，且多处替换均生效。 */
  @Test
  void deduplicatesRepeatedPlaceholders() {
    PromptTemplate template = new PromptTemplate("inline", "repeat ${a} again ${a} and ${b} ${a}");
    assertEquals(List.of("a", "b"), template.variables());
    assertEquals("repeat 1 again 1 and 2 1", template.render(Map.of("a", "1", "b", "2")));
  }

  /** 验证变量值中如果恰好包含 ${...} 文本，不会被进行二次递归解析。 */
  @Test
  void rendersValuesWithoutSecondPassSubstitution() {
    PromptTemplate template = new PromptTemplate("inline", "${a}");
    assertEquals("${b}", template.render(Map.of("a", "${b}")));
  }
}
