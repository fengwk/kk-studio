package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link ToolCallMessageContent} 的 durable 边界：调用身份与 arguments 必填，冻结的 {@code historyAction} / {@code
 * environmentName} 可空但不得为空白。
 */
class ToolCallMessageContentTest {

  /** 旧槽位与 normalization 路径使用 4 参构造，冻结事实为空。 */
  @Test
  void legacyShapeCarriesNoFrozenFacts() {
    ToolCallMessageContent content =
        new ToolCallMessageContent("call-1", "fs_read", "fs.read", "{\"path\":\"a.txt\"}");

    assertNull(content.historyAction());
    assertNull(content.environmentName());
    assertEquals("{}", new ToolCallMessageContent("call-1", "bash", "bash", "{}").argumentsJson());
  }

  /** 冻结的 action 与环境名逐字保留，null 表示未冻结或不绑定环境。 */
  @Test
  void storesFrozenHistoryFactsVerbatim() {
    ToolCallMessageContent content =
        new ToolCallMessageContent(
            "call-1", "fs_read", "fs.read", "{\"path\":\"a.txt\"}", "read a.txt", "dev");

    assertEquals("read a.txt", content.historyAction());
    assertEquals("dev", content.environmentName());
  }

  /** 调用身份、arguments 与冻结事实的 non-blank 边界。 */
  @Test
  void rejectsBlankIdentityArgumentsAndFrozenFacts() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCallMessageContent(" ", "fs_read", "fs.read", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCallMessageContent("call-1", " ", "fs.read", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCallMessageContent("call-1", "fs_read", " ", "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCallMessageContent("call-1", "fs_read", "fs.read", " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCallMessageContent("call-1", "fs_read", "fs.read", "{}", "  ", "dev"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ToolCallMessageContent("call-1", "fs_read", "fs.read", "{}", null, "  "));
  }
}
