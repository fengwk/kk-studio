package fun.fengwk.kkstudio.harness.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class SubagentTaskRequestTest {

  private static final UUID INVOCATION = new UUID(0, 1);
  private static final UUID PARENT = new UUID(0, 2);

  /** 请求本身守住必需身份、非空指令和正预算，不依赖工具 JSON 校验防止内部误用。 */
  @Test
  void rejectsInvalidDelegationFacts() {
    assertThrows(
        NullPointerException.class,
        () -> new SubagentTaskRequest(null, PARENT, "task", "coder", null, null));
    assertThrows(
        NullPointerException.class,
        () -> new SubagentTaskRequest(INVOCATION, null, "task", "coder", null, null));
    for (String blank : new String[] {null, "", " \n"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new SubagentTaskRequest(INVOCATION, PARENT, blank, "coder", null, null));
      assertThrows(
          IllegalArgumentException.class,
          () -> new SubagentTaskRequest(INVOCATION, PARENT, "task", blank, null, null));
    }
    for (int budget : new int[] {0, -1}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new SubagentTaskRequest(INVOCATION, PARENT, "task", "coder", budget, null));
    }
  }

  /** 新建允许缺省预算；继续保留完整指令与目标 Thread，不擅自 trim 或替换身份。 */
  @Test
  void preservesExplicitFactsAndOptionalDefaults() {
    SubagentTaskRequest fresh =
        new SubagentTaskRequest(INVOCATION, PARENT, " task\n", "coder", null, null);
    assertEquals(" task\n", fresh.prompt());
    assertNull(fresh.maxTurns());
    assertNull(fresh.resumeThreadId());
    UUID child = new UUID(0, 3);
    SubagentTaskRequest resumed =
        new SubagentTaskRequest(INVOCATION, PARENT, "continue", "helper", 1, child);
    assertEquals(child, resumed.resumeThreadId());
    assertEquals(1, resumed.maxTurns());
  }
}
