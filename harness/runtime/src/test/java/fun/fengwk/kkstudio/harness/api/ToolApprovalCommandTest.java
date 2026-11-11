package fun.fengwk.kkstudio.harness.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;

import java.util.UUID;

class ToolApprovalCommandTest {

  @Test
  void blankReasonIsNormalizedToMissing() {
    // 空白输入与未填写语义一致，后续拒绝结果使用领域默认原因。
    ToolApprovalCommand command =
        new ToolApprovalCommand(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ToolApprovalDecision.DENIED,
            UUID.randomUUID(),
            "operator",
            "  ");
    assertNull(command.reason());
  }

  @Test
  void nonBlankReasonIsPreserved() {
    ToolApprovalCommand command =
        new ToolApprovalCommand(
            UUID.randomUUID(),
            UUID.randomUUID(),
            ToolApprovalDecision.DENIED,
            UUID.randomUUID(),
            "operator",
            "not allowed");
    assertEquals("not allowed", command.reason());
  }
}
