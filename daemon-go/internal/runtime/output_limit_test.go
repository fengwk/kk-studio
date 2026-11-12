package runtime

import "testing"

// RUN-N-001 verifies the authoritative tool/file messages and precision escalation.
func TestOutputLimitMessagesUseSharedMiBFormatting(t *testing.T) {
	limit := int64(16) * bytesPerMiB
	toolMessage := toolOutputExceededMessage(17_000_000, limit)
	wantTool := "Tool output is 16.2 MiB, which exceeds the tool output size limit of 16 MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings."
	if toolMessage != wantTool {
		t.Fatalf("tool output message = %q", toolMessage)
	}
	fileMessage := fileOutputExceededMessage(limit+1, limit)
	wantFile := "File is 16.000001 MiB, which exceeds the tool output size limit of 16 MiB. The file was not read. Use a smaller file, for example by converting or splitting it with bash, or ask the user to raise the limit in system settings."
	if fileMessage != wantFile {
		t.Fatalf("file output message = %q", fileMessage)
	}
}

func TestFormatActualMiBIncreasesPrecisionWhenRoundedToLimit(t *testing.T) {
	got := formatActualMiB(16*bytesPerMiB+1, 16*bytesPerMiB)
	if got != "16.000001" {
		t.Fatalf("actual size = %q", got)
	}
	if got := formatActualMiB(17*bytesPerMiB, 16*bytesPerMiB); got != "17" {
		t.Fatalf("whole MiB actual size = %q", got)
	}
}
