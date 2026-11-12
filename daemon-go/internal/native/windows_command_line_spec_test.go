package native

import "testing"

// TestWindowsCommandLine_SingleArgumentMatchesJoinedForm checks single-argument quoting without join ambiguity.
func TestWindowsCommandLine_SingleArgumentMatchesJoinedForm(t *testing.T) {
	for _, argument := range []string{"plain", "two words", `tail\\`, `embedded\"quote`} {
		if got, want := ComposeCommandLine([]string{argument}), quoteWindowsArgument(argument); got != want {
			t.Fatalf("single argument %q: got %q, want %q", argument, got, want)
		}
	}
}
