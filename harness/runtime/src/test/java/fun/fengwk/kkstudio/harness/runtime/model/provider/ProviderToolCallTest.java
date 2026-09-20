package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** {@link ProviderToolCall} 的冻结 action 契约：可空但不得为空白，替换 action 不改变调用身份与 arguments。 */
class ProviderToolCallTest {

  /** 不带 action 的调用以 3 参构造表达，action 为 null 表示投影必须回退到通用描述。 */
  @Test
  void actionIsOptional() {
    ProviderToolCall call = new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}");
    assertNull(call.historyAction());
    assertNull(call.withHistoryAction(null).historyAction());
  }

  /** 冻结 action 只替换 action 自身，id / name / durable arguments 逐字保留。 */
  @Test
  void withHistoryActionKeepsCallIdentity() {
    ProviderToolCall frozen =
        new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}", "read a.txt");
    assertEquals("read a.txt", frozen.historyAction());

    ProviderToolCall copied = frozen.withHistoryAction("read b.txt");
    assertEquals(frozen.id(), copied.id());
    assertEquals(frozen.name(), copied.name());
    assertEquals(frozen.argumentsJson(), copied.argumentsJson());
    assertEquals("read b.txt", copied.historyAction());
  }

  /** 空白 action 与空白 arguments 都必须在构造时确定性拒绝。 */
  @Test
  void rejectsBlankActionAndBlankArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProviderToolCall("call-1", "fs_read", "{\"path\":\"a.txt\"}", "  "));
    assertThrows(
        IllegalArgumentException.class, () -> new ProviderToolCall("call-1", "fs_read", " "));
    assertThrows(IllegalArgumentException.class, () -> new ProviderToolCall(" ", "fs_read", "{}"));
    assertThrows(IllegalArgumentException.class, () -> new ProviderToolCall("call-1", " ", "{}"));
  }
}
