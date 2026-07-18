package fun.fengwk.kkstudio.harness.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ArtifactRef;

import java.util.List;

/** TaskReport JSON encode/decode 对称性。 */
class TaskResultFormatterTest {

  @Test
  void jsonRoundTripPreservesFields() {
    TaskReport report =
        new TaskReport(
            11L,
            22L,
            TaskState.SUCCEEDED,
            "done",
            List.of(new ArtifactRef("a1", "text/plain", 3)),
            2,
            1,
            WorkingCopyPolicy.NONE,
            "rev-1");
    String json = TaskResultFormatter.json(report);
    TaskReport decoded = TaskResultFormatter.decodeJson(json);
    assertEquals(report.childSessionId(), decoded.childSessionId());
    assertEquals(report.childThreadId(), decoded.childThreadId());
    assertEquals(report.terminalState(), decoded.terminalState());
    assertEquals(report.finalAssistantReport(), decoded.finalAssistantReport());
    assertEquals(report.turnCount(), decoded.turnCount());
    assertEquals(report.toolCount(), decoded.toolCount());
    assertEquals(report.workingCopyPolicy(), decoded.workingCopyPolicy());
    assertEquals(report.workingCopyRevision(), decoded.workingCopyRevision());
    assertEquals(1, decoded.artifacts().size());
    assertEquals("a1", decoded.artifacts().get(0).artifactId());
  }

  @Test
  void decodeRejectsInvalidJson() {
    assertThrows(IllegalArgumentException.class, () -> TaskResultFormatter.decodeJson("{"));
  }

  @Test
  void decodeRejectsMissingRequiredFields() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TaskResultFormatter.decodeJson("{\"childSessionId\":\"1\"}"));
  }

  @Test
  void decodeRejectsNumericIds() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TaskResultFormatter.decodeJson(
                """
                {"childSessionId":1,"childThreadId":"2","status":"SUCCEEDED","finalReport":"",
                "artifacts":[],"turnCount":0,"toolCount":0,"workingCopyPolicy":"NONE"}
                """));
  }
}
