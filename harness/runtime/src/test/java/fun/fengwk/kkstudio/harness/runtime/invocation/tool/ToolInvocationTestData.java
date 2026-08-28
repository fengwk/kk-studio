package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** invocation.tool 包共享的测试 fixture。 */
final class ToolInvocationTestData {

  static final EnvironmentBinding ENV_ID = EnvironmentBindings.binding("env-1");
  static final String CALL_ID = "call-1";

  private ToolInvocationTestData() {}

  static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        "1.0",
        "description of " + name,
        name,
        new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  static ToolBinding host(String name) {
    return new ToolBinding(
        definition(name, AgentToolBackend.HOST),
        new ContributorBinding("core", name, List.of()),
        null);
  }

  static ToolBinding environment(String name) {
    return new ToolBinding(
        definition(name, AgentToolBackend.ENVIRONMENT_CAPABILITY),
        new ContributorBinding("base", name, List.of()),
        ENV_ID);
  }

  private static AgentToolDefinition definition(String name, AgentToolBackend backend) {
    return new AgentToolDefinition(
        new AgentToolId("test." + name.replace('_', '-').toLowerCase(Locale.ROOT)),
        descriptor(name),
        ToolVisibility.SELECTABLE,
        backend);
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
