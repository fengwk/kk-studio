package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

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

  /** 可空 binding 只属于 durable immediate FAILED 槽位，executable request 必须拒绝（ToolCall 自身也恒非空）。 */
  @Test
  void rejectsNullBindingAndNullCall() {
    assertThrows(
        NullPointerException.class,
        () -> new ToolInvocationRequest(new ToolCall("call-1", "bash", "{}"), null));
    assertThrows(
        NullPointerException.class,
        () -> new ToolInvocationRequest(null, ToolInvocationTestData.platform("bash")));
  }

  /** Tool schema 语义校验已移入 {@link ModelResponsePlanner}，构造器只保留 call 自身的不变量。 */
  @Test
  void rejectsMalformedCallFacts() {
    assertThrows(IllegalArgumentException.class, () -> new ToolCall("call-1", "bash", "[1,2]"));
    assertThrows(IllegalArgumentException.class, () -> new ToolCall("", "bash", "{}"));
  }
}
