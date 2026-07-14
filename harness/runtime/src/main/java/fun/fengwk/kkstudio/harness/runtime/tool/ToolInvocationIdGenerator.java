package fun.fengwk.kkstudio.harness.runtime.tool;

/** ToolInvocation Snowflake id 端口。 */
@FunctionalInterface
public interface ToolInvocationIdGenerator {
  long newInvocationId();
}
