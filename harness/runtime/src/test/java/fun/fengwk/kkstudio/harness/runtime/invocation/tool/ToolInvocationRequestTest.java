package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** ToolInvocationRequest 的 call/binding 对应关系检查。 */
class ToolInvocationRequestTest {

  @Test
  void freezesCallAndBinding() {
    ToolInvocationRequest request = ToolInvocationTestData.request("bash", "{}");

    assertEquals("call-1", request.call().id());
    assertEquals("bash", request.call().toolName());
    assertEquals("{}", request.call().argumentsJson());
    assertEquals("bash", request.binding().descriptor().name());
  }

  @Test
  void rejectsNameMismatchAndInvalidArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocationRequest(
                new ToolCall("call-1", "other", "{}"), ToolInvocationTestData.platform("bash")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocationRequest(
                new ToolCall("call-1", "bash", "[1,2]"), ToolInvocationTestData.platform("bash")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ToolInvocationRequest(
                new ToolCall("call-1", "bash", "{}"), bindingRequiringPath()));
  }

  @Test
  void rejectsNullFacts() {
    assertThrows(
        NullPointerException.class,
        () -> new ToolInvocationRequest(null, ToolInvocationTestData.platform("bash")));
    assertThrows(
        NullPointerException.class,
        () -> new ToolInvocationRequest(new ToolCall("call-1", "bash", "{}"), null));
  }

  private static ToolBinding bindingRequiringPath() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "bash",
            "1.0",
            ToolType.PLATFORM,
            "desc",
            "bash",
            new ToolParamsSchema(
                "arguments", Map.of("path", new ToolStringSchema(null)), Set.of("path"), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    return new ToolBinding(descriptor, ToolType.PLATFORM, null);
  }
}
