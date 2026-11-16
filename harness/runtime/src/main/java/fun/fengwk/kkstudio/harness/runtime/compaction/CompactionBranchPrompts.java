package fun.fengwk.kkstudio.harness.runtime.compaction;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionBranchContract;

/** Fixed internal instruction text for a COMPACTION branch. */
public final class CompactionBranchPrompts {

  private static final String INSTRUCTION_TEMPLATE =
      CompactionBranchContract.INSTRUCTION_PREFIX
          + """
          This conversation is being compacted. The conversation above will be replaced by your summary so the work can continue in a fresh context. The most recent messages are not shown here; they are kept verbatim and will follow your summary.

          Rules for this mode:
          - Tools are disabled. Tool calls are not executed and only return an error.
          - Do not continue the task or respond to pending requests. Your only job is the summary.
          - If the conversation above contains an earlier summary, merge it in: keep what is still true, update what changed, and remove what is obsolete.
          - Preserve exact file paths, function names, commands, error messages, and resource:// URIs.
          - List every file and resource needed to continue the work, and only those.
          - Keep the summary under {{maxTokens}} tokens.

          Use this exact format:

          ## Goal
          [What the user is trying to accomplish]

          ## Constraints & Preferences
          - [Requirements and preferences stated by the user, or "(none)"]

          ## Progress
          ### Done
          - [x] [Completed work]

          ### In Progress
          - [ ] [Current work]

          ### Blocked
          - [Blockers, if any]

          ## Key Decisions
          - **[Decision]**: [Rationale]

          ## Next Steps
          1. [What should happen next]

          ## Critical Context
          - [Data, examples, or references needed to continue, or "(none)"]

          ## Relevant Files
          - `[path]`: [Why it is needed to continue]

          ## Relevant Resources
          - `[resource://...]`: [Why it is needed to continue]

          Reply with the summary only.
          """;

  private CompactionBranchPrompts() {}

  public static String instruction(long maxTokens) {
    if (maxTokens <= 0) {
      throw new IllegalArgumentException("maxTokens must be positive");
    }
    return INSTRUCTION_TEMPLATE.replace("{{maxTokens}}", Long.toString(maxTokens));
  }
}
