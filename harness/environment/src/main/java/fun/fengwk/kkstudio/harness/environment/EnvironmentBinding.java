package fun.fengwk.kkstudio.harness.environment;

import java.util.Objects;

/**
 * Branch/Invocation 冻结的完整 Environment 绑定：canonical 路由身份 + canonical workspace path。
 *
 * <p>record 本身两字段均 non-null：{@code environmentId} 是 durable 路由身份（{@link EnvironmentId}）， {@code
 * workspacePath} 是 Environment Root 下的 canonical 相对 wire 路径（{@code '.'} 表示 root，规则见 {@link
 * EnvironmentWorkspacePath}）。null binding（未选择 Environment）由引用方以 Java null 表示，绝不构造半空
 * record。Capability 执行时由 daemon 把 workspace path canonicalize 为 Environment Root 内现存目录并作为默认 cwd。
 */
public record EnvironmentBinding(EnvironmentId environmentId, String workspacePath) {

  public EnvironmentBinding {
    environmentId = Objects.requireNonNull(environmentId, "environmentId");
    workspacePath = EnvironmentWorkspacePath.requireCanonicalRelativePath(workspacePath);
  }
}
