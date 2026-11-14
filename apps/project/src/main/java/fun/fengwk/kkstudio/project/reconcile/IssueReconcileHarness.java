package fun.fengwk.kkstudio.project.reconcile;

import lombok.AllArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.project.platform.IssueThreadExecution;

/** 调谐器访问 Harness 运行时的唯一入口：部署缺少 Runtime 时确定失败，绝不退化为"无事实"的安全收尾。 */
@Component
@AllArgsConstructor
final class IssueReconcileHarness {

  private final ObjectProvider<IssueThreadExecution> threadExecutions;

  IssueThreadExecution require() {
    IssueThreadExecution port = threadExecutions.getIfAvailable();
    if (port == null) {
      throw new IllegalStateException("harness runtime is not available in this deployment");
    }
    return port;
  }
}
