package fun.fengwk.kkstudio.harness.runtime.input;

/**
 * 冻结问卷中的一个选项：模型只提供展示文本与 recommendation 标记，不生成 optionId。
 *
 * <p>{@code recommended} 仅做标记，绝不代替用户选择；同一问题内的 {@code label} 必须唯一，比较与答案匹配都使用 label 原文。label 与
 * description 不设业务字符上限。
 */
public record HumanInputOption(String label, String description, boolean recommended) {

  public HumanInputOption {
    label = HumanInputTexts.requireText(label, "label");
    description = HumanInputTexts.nullableText(description, "description");
  }
}
