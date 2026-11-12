package fun.fengwk.kkstudio.common.result;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToolOutputLimitTest {

  /** These vectors are mirrored in daemon/test/tool-output-limit.test.ts. */
  @Test
  void formatsSharedSizeVectorsAndMessages() {
    long limit = 16L * 1_048_576L;

    assertEquals(
        "Tool output is 16.2 MiB, which exceeds the tool output size limit of 16 MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.",
        ToolOutputLimit.exceededMessage(17_000_000L, limit));
    assertEquals(
        "Tool output is 16.000001 MiB, which exceeds the tool output size limit of 16 MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings.",
        ToolOutputLimit.exceededMessage(16_777_217L, limit));
    assertEquals(
        "File is 16.000001 MiB, which exceeds the tool output size limit of 16 MiB. The file was not read. Use a smaller file, for example by converting or splitting it with bash, or ask the user to raise the limit in system settings.",
        ToolOutputLimit.fileExceededMessage(16_777_217L, limit));
  }
}
