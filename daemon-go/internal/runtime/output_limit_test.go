package runtime

import (
	"testing"
)

// RUN-N-001 verifies the authoritative tool message and precision escalation.
func TestToolOutputLimitMessageUsesSharedMiBFormatting(t *testing.T) {
	limit := int64(16) * bytesPerMiB
	toolMessage := toolOutputExceededMessage(17_000_000, limit)
	wantTool := "Tool output is 16.2 MiB, which exceeds the tool output size limit of 16 MiB. The tool ran but its output was discarded; side effects may have occurred. Request a smaller result, for example a narrower read range or filtered command output, or ask the user to raise the limit in system settings."
	if toolMessage != wantTool {
		t.Fatalf("tool output message = %q", toolMessage)
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
