package fun.fengwk.kkstudio.harness.api.compaction;

/**
 * COMPACTION branch 的固定内部文本契约：摘要指令前缀与工具禁用回执。
 *
 * <p>这两段文本同时约束历史文法与生成行为：{@code TurnPathValidator} 据前缀识别分支首轮内部指令，Builtin Driver
 * 用同一回执替换被禁用的工具调用，Runtime 的 prompt 构造复用前缀。因此它们属于公开契约，而不是 Runtime 私有文案。
 */
public final class CompactionBranchContract {

  /** 分支首轮内部摘要指令的固定前缀。 */
  public static final String INSTRUCTION_PREFIX = "[Compaction mode]\n";

  /** COMPACTION branch 内任何平台工具调用返回的固定失败文本。 */
  public static final String TOOL_ERROR =
      "[Compaction mode] Tools are disabled, so this call was not executed. Do not continue the task. Write the summary requested in the compaction instruction now, in its exact format (Goal, Constraints & Preferences, Progress, Key Decisions, Next Steps, Critical Context, Relevant Files, Relevant Resources), and reply with the summary only.";

  private CompactionBranchContract() {}
}
