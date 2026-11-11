package native

import "testing"

// NAT-T-159..165
func TestWindowsCommandLine_Quoting(t *testing.T) {
	cases := []struct {
		name string
		args []string
		want string
	}{
		{"safe", []string{"one", "two"}, "one two"},
		{"space", []string{"has space"}, `"has space"`},
		{"empty", []string{""}, `""`},
		{"backslash", []string{`a\b`}, `a\b`},
		{"trailing", []string{`a b\`}, `"a b\\"`},
		{"quote", []string{"a\"b"}, "\"a\\\"b\""},
		{"quote and trailing slash", []string{"a\\\" b\\"}, "\"a\\\\\\\" b\\\\\""},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			if got := ComposeCommandLine(test.args); got != test.want {
				t.Fatalf("ComposeCommandLine(%q) = %q, want %q", test.args, got, test.want)
			}
		})
	}
}
