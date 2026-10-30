package fun.fengwk.kkstudio.harness.contributor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ToolCall;

/** {@link ToolHistoryRenderRequest} 的最小输入契约：call 必填，environmentName 只允许 null 或非空白。 */
class ToolHistoryRenderRequestTest {

  /** 不绑定 Environment 的调用以 null 表达；绑定时逐字保留冻结的环境名。 */
  @Test
  void acceptsNullOrNonBlankEnvironmentName() {
    ToolCall call = new ToolCall("call-1", "read_file", "{\"path\":\"/tmp/a\"}");
    assertNull(new ToolHistoryRenderRequest(call, null).environmentName());
    assertEquals("dev", new ToolHistoryRenderRequest(call, "dev").environmentName());
    assertEquals(call, new ToolHistoryRenderRequest(call, null).call());
  }

  /** call 缺失或 environmentName 为空白文本都必须在渲染之前确定性失败。 */
  @Test
  void rejectsMissingCallAndBlankEnvironmentName() {
    ToolCall call = new ToolCall("call-1", "read_file", "{}");
    assertThrows(NullPointerException.class, () -> new ToolHistoryRenderRequest(null, null));
    assertThrows(IllegalArgumentException.class, () -> new ToolHistoryRenderRequest(call, " "));
  }
}
