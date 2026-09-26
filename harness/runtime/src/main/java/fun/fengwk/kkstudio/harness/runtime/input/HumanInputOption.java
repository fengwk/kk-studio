package fun.fengwk.kkstudio.harness.runtime.input;

/**
 * 冻结问卷中的一个选项：模型只提供展示文本与 recommendation 标记，不生成 optionId。
 *
 * <p>{@code recommended} 仅做标记，绝不代替用户选择；同一问题内的 {@code label} 必须唯一，比较与答案匹配都使用 label 原文。
 */
public record HumanInputOption(String label, String description, boolean recommended) {

  /** 选项 label 的字符上限。 */
  public static final int MAX_LABEL_CHARACTERS = 256;

  /** 选项 description 的字符上限。 */
  public static final int MAX_DESCRIPTION_CHARACTERS = 1024;

  public HumanInputOption {
    label = HumanInputTexts.requireText(label, "label", MAX_LABEL_CHARACTERS);
    description =
        HumanInputTexts.nullableText(description, "description", MAX_DESCRIPTION_CHARACTERS);
  }
}
