package fun.fengwk.kkstudio.harness.api.command;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.CanonicalNames;

/**
 * command payload 与 wire codec 共用的 canonical 名称校验入口。
 *
 * <p>Agent name 与 Environment name 的约束必须与 {@link BranchSettings} 完全一致，否则同一值会通过 command codec 却在
 * reducer 应用时失败；规则本身只在 {@link CanonicalNames} 与 {@link BranchSettings} 各实现一次，本类只做入参适配。
 */
public final class CommandValueValidation {

  private CommandValueValidation() {}

  public static String requireCanonicalName(String value, String field) {
    return CanonicalNames.requireName(value, field);
  }

  /** nullable Environment 名：null 表示解除环境选择；非 null 复用 {@link BranchSettings} 的 canonical 校验。 */
  public static String requireCanonicalEnvironmentName(String value, String field) {
    return BranchSettings.requireCanonicalEnvironmentName(value, field);
  }
}
