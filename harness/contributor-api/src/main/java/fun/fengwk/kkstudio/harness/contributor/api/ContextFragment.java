package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.Objects;

/**
 * 由 {@link ContextProjector} 投影并注入模型上下文的文本片段。
 *
 * @param text 文本内容，不得为 null
 */
public record ContextFragment(String text) {

  public ContextFragment {
    Objects.requireNonNull(text, "text");
  }
}
