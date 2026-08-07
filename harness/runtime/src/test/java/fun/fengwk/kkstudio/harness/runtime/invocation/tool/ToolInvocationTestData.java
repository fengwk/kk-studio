package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** invocation.tool 包共享的测试 fixture。 */
final class ToolInvocationTestData {

  static final EnvironmentName ENV_ID = new EnvironmentName("env-1");
  static final String CALL_ID = "call-1";

  private ToolInvocationTestData() {}

  static ToolDescriptor descriptor(String name, ToolType type) {
    return new ToolDescriptor(
        name,
        "1.0",
        type,
        "description of " + name,
        null,
        new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  static ToolBinding platform(String name) {
    return new ToolBinding(descriptor(name, ToolType.PLATFORM), ToolType.PLATFORM, null);
  }

  static ToolBinding environment(String name) {
    return new ToolBinding(descriptor(name, ToolType.ENVIRONMENT), ToolType.ENVIRONMENT, ENV_ID);
  }

  static ToolInvocationRequest request(String name, String argumentsJson) {
    return new ToolInvocationRequest(new ToolCall(CALL_ID, name, argumentsJson), platform(name));
  }
}
