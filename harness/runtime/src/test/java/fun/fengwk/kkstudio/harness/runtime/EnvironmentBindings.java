package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

/**
 * 测试共享 fixture：把 canonical Environment 名称包装为完整 {@link EnvironmentBinding}。
 *
 * <p>workspace path 固定为 root {@code '.'}；需要非 root workspace 的测试直接构造 {@link EnvironmentBinding}。
 */
public final class EnvironmentBindings {

  private EnvironmentBindings() {}

  public static EnvironmentBinding binding(String name) {
    return new EnvironmentBinding(new EnvironmentName(name), ".");
  }

  public static EnvironmentBinding binding(EnvironmentName name) {
    return new EnvironmentBinding(name, ".");
  }
}
