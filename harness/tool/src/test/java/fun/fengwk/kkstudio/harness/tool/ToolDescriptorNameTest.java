package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link ToolDescriptor} 模型可见 name 的唯一身份规则与 64 字符上限。 */
class ToolDescriptorNameTest {

  /** 意图：锁定 name 的 64 字符硬上限——恰好 64 接受、65 拒绝，长度是唯一的截断边界。 */
  @Test
  void enforcesExact64CharacterNameLimit() {
    String atLimit = "a".repeat(ToolDescriptor.NAME_MAX_LENGTH);
    String overLimit = "a".repeat(ToolDescriptor.NAME_MAX_LENGTH + 1);

    assertEquals(64, ToolDescriptor.NAME_MAX_LENGTH);
    assertTrue(ToolDescriptor.isValidName(atLimit), atLimit);
    assertFalse(ToolDescriptor.isValidName(overLimit), overLimit);
  }

  /** 意图：锁定 name 的语法——必须字母开头，只允许字母数字与 {@code _}、{@code -}。 */
  @Test
  void enforcesNameSyntax() {
    assertTrue(ToolDescriptor.isValidName("read"));
    assertTrue(ToolDescriptor.isValidName("mcp_server_echo"));
    assertTrue(ToolDescriptor.isValidName("issue-add-dependency"));
    assertTrue(ToolDescriptor.isValidName("a1"));

    assertFalse(ToolDescriptor.isValidName(null));
    assertFalse(ToolDescriptor.isValidName(""));
    assertFalse(ToolDescriptor.isValidName("1bash"));
    assertFalse(ToolDescriptor.isValidName("_bash"));
    assertFalse(ToolDescriptor.isValidName("-bash"));
    assertFalse(ToolDescriptor.isValidName("base.read"));
    assertFalse(ToolDescriptor.isValidName("read "));
    assertFalse(ToolDescriptor.isValidName(" read"));
    assertFalse(ToolDescriptor.isValidName("re ad"));
  }
}
