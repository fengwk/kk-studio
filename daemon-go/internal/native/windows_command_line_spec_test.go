package native

import "testing"

// TestWindowsCommandLine_SingleArgumentMatchesJoinedForm checks single-argument quoting without join ambiguity.
func TestWindowsCommandLine_SingleArgumentMatchesJoinedForm(t *testing.T) {
	for _, test := range []struct {
		argument string
		want     string
	}{
		{argument: "plain", want: "plain"},
		{argument: "two words", want: `"two words"`},
		{argument: `tail\`, want: `tail\`},
		{argument: `embedded"quote`, want: `"embedded\"quote"`},
	} {
		if got := ComposeCommandLine([]string{test.argument}); got != test.want {
			t.Fatalf("single argument %q: got %q, want %q", test.argument, got, test.want)
		}
	}
}
