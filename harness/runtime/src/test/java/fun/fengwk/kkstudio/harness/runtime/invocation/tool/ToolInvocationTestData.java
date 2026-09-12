package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** invocation.tool 包共享的测试 fixture。 */
final class ToolInvocationTestData {

  static final EnvironmentId ENV_ID = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  static final String CALL_ID = "call-1";

  private ToolInvocationTestData() {}

  static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        "1.0",
        "description of " + name,
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  static ToolBinding host(String name) {
    return new ToolBinding(
        definition(name), new ContributorBinding("core", name, List.of()), false, null);
  }

  static ToolBinding environment(String name) {
    return new ToolBinding(
        definition(name), new ContributorBinding("base", name, List.of()), true, ENV_ID);
  }

  private static AgentToolDefinition definition(String name) {
    return new AgentToolDefinition(
        new AgentToolId("test." + name.replace('_', '-').toLowerCase(Locale.ROOT)),
        descriptor(name),
        ToolVisibility.SELECTABLE);
  }

  static ToolCall call(String name, String argumentsJson) {
    return new ToolCall(CALL_ID, name, argumentsJson);
  }

  static ToolCall call(String name) {
    return call(name, "{}");
  }

  /** transient executable request（READY Tool 边界使用；不持久化）。 */
  static ToolInvocationRequest request(String name, String argumentsJson) {
    return new ToolInvocationRequest(call(name, argumentsJson), host(name));
  }
}
