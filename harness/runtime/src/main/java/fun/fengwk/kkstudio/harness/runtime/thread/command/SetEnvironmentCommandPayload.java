package fun.fengwk.kkstudio.harness.runtime.thread.command;

/**
 * SET_ENVIRONMENT 的 typed payload。
 *
 * <p>{@code environmentName} 可为 null，表示解除该 branch 的 Environment 选择；非 null 时必须是 canonical
 * Environment name（非 blank、无首尾空白、不含 {@code '/'}、≤64 字符）。
 */
public record SetEnvironmentCommandPayload(String environmentName) implements ThreadCommandPayload {

  public SetEnvironmentCommandPayload {
    environmentName =
        CommandValueValidation.requireCanonicalEnvironmentName(environmentName, "environmentName");
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_ENVIRONMENT;
  }
}
