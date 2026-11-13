package fun.fengwk.kkstudio.harness.runtime.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Fixed branch texts are contract strings; only the summary token cap is substituted. */
class CompactionBranchPromptsTest {

  @Test
  void instructionSubstitutesTheOutputCapAndErrorTextIsFixed() {
    String instruction = CompactionBranchPrompts.instruction(160);

    assertTrue(instruction.startsWith("[Compaction mode]\n"));
    assertTrue(instruction.contains("- Keep the summary under 160 tokens."));
    assertFalse(instruction.contains("{{maxTokens}}"));
    assertEquals(
        "[Compaction mode] Tools are disabled, so this call was not executed. Do not continue the task. Write the summary requested in the compaction instruction now, in its exact format (Goal, Constraints & Preferences, Progress, Key Decisions, Next Steps, Critical Context, Relevant Files, Relevant Resources), and reply with the summary only.",
        CompactionBranchPrompts.TOOL_ERROR);
  }

  @Test
  void instructionRequiresPositiveOutputCap() {
    assertThrows(IllegalArgumentException.class, () -> CompactionBranchPrompts.instruction(0));
  }
}
