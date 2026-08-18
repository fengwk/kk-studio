package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

/** ToolInvocationRequest 的 call/binding 冻结语义：binding 只对 immediate FAILED 槽位可空。 */
class ToolInvocationRequestTest {

  @Test
  void freezesCallAndBinding() {
    ToolInvocationRequest request = ToolInvocationTestData.request("bash", "{}");

    assertEquals("call-1", request.call().id());
    assertEquals("bash", request.call().toolName());
    assertEquals("{}", request.call().argumentsJson());
    assertEquals("bash", request.binding().descriptor().name());
  }

  /** binding 可空只用于 immediate FAILED 槽位（unknown tool / 输出截断）；ToolCall 本身仍非空。 */
  @Test
  void allowsNullableBindingForImmediateFailedSlots() {
    ToolInvocationRequest request =
        new ToolInvocationRequest(new ToolCall("call-1", "bash", "{}"), null);

    assertEquals("call-1", request.call().id());
    assertNull(request.binding());
  }

  /** Tool schema 语义校验已移入 {@link ModelResponsePlanner}，构造器只保留 call 自身的不变量。 */
  @Test
  void rejectsNullCallAndMalformedCallFacts() {
    assertThrows(
        NullPointerException.class,
        () -> new ToolInvocationRequest(null, ToolInvocationTestData.platform("bash")));
    assertThrows(IllegalArgumentException.class, () -> new ToolCall("call-1", "bash", "[1,2]"));
    assertThrows(IllegalArgumentException.class, () -> new ToolCall("", "bash", "{}"));
  }
}
