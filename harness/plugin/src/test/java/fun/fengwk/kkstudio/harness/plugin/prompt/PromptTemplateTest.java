package fun.fengwk.kkstudio.harness.plugin.prompt;

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

  private static final String GREETING = "fun/fengwk/kkstudio/harness/plugin/prompt/greeting.txt";
  private static final String UNTERMINATED =
      "fun/fengwk/kkstudio/harness/plugin/prompt/unterminated.txt";
  private static final String INVALID_NAME =
      "fun/fengwk/kkstudio/harness/plugin/prompt/invalid-name.txt";

  private final PromptTemplateLoader loader = new PromptTemplateLoader();

  @Test
  void loadsAndRendersWithExactVariables() {
    PromptTemplate template = loader.load(GREETING);
    assertEquals("Hello ${name}, your task is ${task}.\n", template.raw());
    assertEquals(List.of("name", "task"), template.variables());
    assertEquals(
        "Hello Alice, your task is write tests.\n",
        template.render(Map.of("name", "Alice", "task", "write tests")));
  }

  @Test
  void cachesRawResourcesAcrossLoads() {
    assertSame(loader.load(GREETING), loader.load(GREETING));
    assertNotSame(loader.load(GREETING), new PromptTemplateLoader().load(GREETING));
  }

  @Test
  void rejectsMissingResource() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> loader.load("fun/fengwk/kkstudio/harness/plugin/prompt/missing.txt"));
    assertTrue(error.getMessage().contains("missing classpath prompt template resource"));
    assertThrows(IllegalArgumentException.class, () -> loader.load(""));
    assertThrows(NullPointerException.class, () -> loader.load(null));
  }

  @Test
  void rejectsUnterminatedAndInvalidPlaceholdersAtLoadTime() {
    IllegalArgumentException unterminated =
        assertThrows(IllegalArgumentException.class, () -> loader.load(UNTERMINATED));
    assertTrue(unterminated.getMessage().contains("unterminated placeholder"));
    IllegalArgumentException invalid =
        assertThrows(IllegalArgumentException.class, () -> loader.load(INVALID_NAME));
    assertTrue(invalid.getMessage().contains("invalid placeholder variable name"));
  }

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

  @Test
  void deduplicatesRepeatedPlaceholders() {
    PromptTemplate template = new PromptTemplate("inline", "repeat ${a} again ${a} and ${b} ${a}");
    assertEquals(List.of("a", "b"), template.variables());
    assertEquals("repeat 1 again 1 and 2 1", template.render(Map.of("a", "1", "b", "2")));
  }

  @Test
  void rendersValuesWithoutSecondPassSubstitution() {
    PromptTemplate template = new PromptTemplate("inline", "${a}");
    assertEquals("${b}", template.render(Map.of("a", "${b}")));
  }
}
