package fun.fengwk.kkstudio.harness.api.history;

import fun.fengwk.kkstudio.harness.api.entry.CanonicalNames;

import java.util.Objects;

/** 回合可委派的子代理名称与展示描述快照，是 TurnCapabilities 的组成部分。 */
public record SubagentBinding(String name, String description) {

  /** 可委派子代理名称的字符上限。 */
  private static final int NAME_MAX_CHARS = 64;

  public SubagentBinding {
    name = CanonicalNames.requireName(name, "name", NAME_MAX_CHARS);
    description = Objects.requireNonNull(description, "description");
  }
}
