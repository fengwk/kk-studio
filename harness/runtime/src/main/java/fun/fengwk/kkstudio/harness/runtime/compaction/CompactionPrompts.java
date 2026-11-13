package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;

import java.util.Map;

/** Renders the summary wrapper used when materializing the next conversation request. */
public final class CompactionPrompts {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/prompts/compaction/";

  private CompactionPrompts() {}

  /** Wraps a completed summary as the user-context message for subsequent requests. */
  public static String compactedContext(String summary) {
    return PromptRenderer.render(ROOT + "compacted-context.md", Map.of("summary", summary));
  }
}
