package fun.fengwk.kkstudio.harness.runtime.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;

import java.util.Map;

class CompactionPromptsTest {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/prompts/compaction/";

  @Test
  void compactedContextWrapsTheSummaryVerbatim() {
    String summary = "the summary";

    assertEquals(
        PromptRenderer.render(ROOT + "compacted-context.md", Map.of("summary", summary)),
        CompactionPrompts.compactedContext(summary));
  }
}
