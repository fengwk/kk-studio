package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** task 调用约定在工具说明、委派策略在 system 段，每条只出现一次。 */
class SubagentPromptsTest {

  @Test
  void keepsCallContractAndDelegationPolicyApart() {
    // system 段渲染调用方配置的默认值；工具说明不带默认值，两段不共享任何句子。
    String toolDescription = SubagentPrompts.taskToolDescription();
    String systemInstructions = SubagentPrompts.systemInstructions(42);

    assertTrue(systemInstructions.contains("The default `max_turns` is `42`."), systemInstructions);
    assertFalse(toolDescription.contains("The default `max_turns` is"), toolDescription);
    Set<String> shared = sentences(toolDescription);
    shared.retainAll(sentences(systemInstructions));
    assertEquals(Set.of(), shared);
  }

  private static Set<String> sentences(String text) {
    return text.lines()
        .flatMap(line -> Arrays.stream(line.split("(?<=\\.)\\s+")))
        .map(sentence -> sentence.replaceFirst("^[-*]\\s+", "").strip())
        .filter(sentence -> !sentence.isEmpty())
        .collect(Collectors.toSet());
  }
}
