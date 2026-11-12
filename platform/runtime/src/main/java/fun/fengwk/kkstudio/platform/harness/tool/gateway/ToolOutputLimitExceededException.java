package fun.fengwk.kkstudio.platform.harness.tool.gateway;

/** Identifies a limit rejection so finalization can preserve its model-visible explanation. */
final class ToolOutputLimitExceededException extends RuntimeException {

  ToolOutputLimitExceededException(String message) {
    super(message);
  }
}
