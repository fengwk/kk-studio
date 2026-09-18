package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ToolInvocationRequest 的 call/binding 冻结语义：executable request 只面向 READY Tool，binding 恒非空。 */
class ToolInvocationRequestTest {

  @Test
  void freezesCallAndBinding() {
    ToolInvocationRequest request = ToolInvocationTestData.request("bash", "{}");

    assertEquals("call-1", request.call().id());
    assertEquals("bash", request.call().toolName());
    assertEquals("{}", request.call().argumentsJson());
    assertEquals("bash", request.binding().descriptor().name());
  }

  /** transient request 在权限与执行之前固化归一化参数，durable raw call 保持不变。 */
  @Test
  void normalizesExecutableCallWithoutMutatingDurableCall() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "read",
            "read",
            "read",
            new InputSchema(
                "arguments",
                Map.of("path", new StringSchema(null), "offset", new IntegerSchema(null)),
                Set.of(),
                false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    ToolBinding binding =
        new ToolBinding(
            new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE),
            new ContributorBinding("core", "read", List.of()),
            false,
            null);
    ToolCall durable = new ToolCall("call-1", "read", "{\"path\":null,\"offset\":\"10\"}");

    ToolInvocationRequest request = new ToolInvocationRequest(durable, binding);

    assertEquals("{\"offset\":10}", request.call().argumentsJson());
    assertEquals("{\"path\":null,\"offset\":\"10\"}", durable.argumentsJson());
  }

  /** 可空 binding 只属于 durable immediate FAILED 槽位，executable request 必须拒绝（ToolCall 自身也恒非空）。 */
  @Test
  void rejectsNullBindingAndNullCall() {
    assertThrows(
        NullPointerException.class,
        () -> new ToolInvocationRequest(new ToolCall("call-1", "bash", "{}"), null));
    assertThrows(
        NullPointerException.class,
        () -> new ToolInvocationRequest(null, ToolInvocationTestData.host("bash")));
  }

  /** Planner 会提前拒绝非法 schema；transient 构造器仍不能接受绕过 planner 的非法调用。 */
  @Test
  void rejectsMalformedCallFacts() {
    assertThrows(IllegalArgumentException.class, () -> new ToolCall("call-1", "bash", "[1,2]"));
    assertThrows(IllegalArgumentException.class, () -> new ToolCall("", "bash", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ToolInvocationTestData.request("bash", "{\"unexpected\":true}"));
  }
}
