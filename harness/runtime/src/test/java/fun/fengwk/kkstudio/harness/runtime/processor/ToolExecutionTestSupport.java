package fun.fengwk.kkstudio.harness.runtime.processor;

/** 测试专用的 ToolExecution 包内状态访问。 */
public final class ToolExecutionTestSupport {

  private ToolExecutionTestSupport() {}

  public static boolean isAbandoned(ToolExecution execution) {
    return execution.abandoned();
  }

  public static boolean markRunning(ToolExecution execution) {
    return execution.markRunning();
  }
}
