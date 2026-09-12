package fun.fengwk.kkstudio.platform.environment.operation;

/** environment_operation 标准失败原因码与安全描述常量。 */
public final class EnvironmentOperationFailureCodes {

  private EnvironmentOperationFailureCodes() {}

  public static final String ENVIRONMENT_UNAVAILABLE_TIMEOUT = "ENVIRONMENT_UNAVAILABLE_TIMEOUT";
  public static final String RESULT_TIMEOUT = "RESULT_TIMEOUT";
  public static final String DISPATCHER_SHUTDOWN = "DISPATCHER_SHUTDOWN";
  public static final String OPERATION_FAILED = "OPERATION_FAILED";
  public static final String INVALID_RESULT = "INVALID_RESULT";
  public static final String RESOURCE_CHANGED = "RESOURCE_CHANGED";
  public static final String TRANSPORT_ERROR = "TRANSPORT_ERROR";

  public static final String UNCLAIMED_TIMEOUT_MESSAGE =
      "Operation deadline elapsed before being claimed by an active node";
  public static final String UNSENT_TIMEOUT_MESSAGE =
      "Environment unavailable before execution started; operation deadline elapsed";
  public static final String RESULT_TIMEOUT_MESSAGE =
      "Operation deadline elapsed before execution finished";
  public static final String DISPATCHER_SHUTDOWN_MESSAGE =
      "Dispatcher node shutting down gracefully";
  public static final String OPERATION_FAILED_MESSAGE = "Operation execution reported failure";
  public static final String INVALID_RESULT_MESSAGE =
      "Operation result payload is invalid or malformed";
  public static final String RESOURCE_CHANGED_MESSAGE =
      "Source configuration changed before operation completed";
  public static final String TRANSPORT_ERROR_MESSAGE =
      "Capability execution failed due to transport or connection error";
}
