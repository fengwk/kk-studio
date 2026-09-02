package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

/**
 * 测试共享 fixture：把 canonical Environment id 文本包装为完整 {@link EnvironmentBinding}。
 *
 * <p>workspace path 固定为 root {@code '.'}；需要非 root workspace 的测试直接构造 {@link EnvironmentBinding}。
 */
public final class EnvironmentBindings {

  private EnvironmentBindings() {}

  public static EnvironmentBinding binding(String id) {
    return new EnvironmentBinding(EnvironmentId.parse(id), ".");
  }

  public static EnvironmentBinding binding(EnvironmentId id) {
    return new EnvironmentBinding(id, ".");
  }
}
