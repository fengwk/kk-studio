package fun.fengwk.kkstudio.harness.runtime.input;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 冻结问卷中的一个问题：单选（默认）或多选，可携带若干选项；选项可以为空，表示只接受自定义回答。
 *
 * <p>答案按问题位置对应，因此问题本身没有 questionId。单选至多一个 {@code recommended}，多选可以有多个；同一问题内 label 唯一。
 * 每题都固定允许一项自定义文本回答（例如选项不足以表达时），但一个问题的自定义回答至多一项，见 {@link HumanInputAnswers}。问题文本与选项数不设业务上限。
 */
public record HumanInputQuestion(
    String question, boolean multiple, List<HumanInputOption> options) {

  public HumanInputQuestion {
    question = HumanInputTexts.requireText(question, "question");
    options = List.copyOf(Objects.requireNonNull(options, "options"));
    Set<String> labels = new HashSet<>();
    int recommended = 0;
    for (HumanInputOption option : options) {
      if (!labels.add(option.label())) {
        throw new IllegalArgumentException("a question must not declare duplicate option labels");
      }
      if (option.recommended()) {
        recommended++;
      }
    }
    if (!multiple && recommended > 1) {
      throw new IllegalArgumentException(
          "a single choice question allows at most one recommended option");
    }
  }

  /** 该 label 在问题中的选项下标（用于按问卷顺序规范化）；未声明时返回 -1，即自定义回答。 */
  public int optionIndex(String label) {
    for (int index = 0; index < options.size(); index++) {
      if (options.get(index).label().equals(label)) {
        return index;
      }
    }
    return -1;
  }
}
