package fun.fengwk.kkstudio.harness.common.tool;

import java.util.Objects;

/**
 * 模型可见工具错误的三段式英文文案：发生了什么、执行事实、下一步。
 *
 * <p>验证阶段（派发前）的确定性拒绝只能说 {@link ExecutionFact#NOT_EXECUTED}；已经进入执行、或结果无法确认的失败分别使用 {@link
 * ExecutionFact#FAILED} / {@link ExecutionFact#UNCERTAIN}，绝不能声称「未执行」。所有文案只陈述事实与恢复方式，不包含重试倾向性指令。
 */
public final class ToolErrorGuidance {

  /** 工具执行事实：决定错误文案能否声称「未执行」。 */
  public enum ExecutionFact {
    /** 派发前确定性拒绝：没有开始执行，也没有任何副作用。 */
    NOT_EXECUTED("The tool was not executed."),
    /** 已经进入执行但确定失败：可能已产生部分副作用，不能假设未执行。 */
    FAILED("The tool ran but failed; side effects may have occurred."),
    /** 超时、提交不明或远程断开等：结果无法确认，同样不能假设未执行。 */
    UNCERTAIN("Whether the tool took effect cannot be confirmed.");

    private final String fact;

    ExecutionFact(String fact) {
      this.fact = fact;
    }

    /** 该执行事实的固定英文句子。 */
    public String fact() {
      return fact;
    }
  }

  private ToolErrorGuidance() {}

  /**
   * 组装三段式文案：{@code whatFailed + 执行事实 + nextAction}。
   *
   * @param whatFailed 发生了什么，非空；不得包含原始凭据或异常栈
   * @param execution 工具执行事实
   * @param nextAction 下一步可执行动作，非空
   */
  public static String message(String whatFailed, ExecutionFact execution, String nextAction) {
    Objects.requireNonNull(execution, "execution");
    return sentence(whatFailed) + " " + execution.fact() + " " + sentence(nextAction);
  }

  /** 文案是否已带三段式执行事实；用于避免对同一错误重复包装。 */
  public static boolean isGuided(String text) {
    if (text == null) {
      return false;
    }
    for (ExecutionFact fact : ExecutionFact.values()) {
      if (text.contains(fact.fact())) {
        return true;
      }
    }
    return false;
  }

  private static String sentence(String text) {
    String stripped = Objects.requireNonNull(text, "text").strip();
    if (stripped.isEmpty()) {
      throw new IllegalArgumentException("text must not be blank");
    }
    char last = stripped.charAt(stripped.length() - 1);
    return (last == '.' || last == '!' || last == '?') ? stripped : stripped + ".";
  }
}
