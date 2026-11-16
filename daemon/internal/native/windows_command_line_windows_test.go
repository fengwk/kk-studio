//go:build windows

package native

import (
	"reflect"
	"testing"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Windows' real parser must recover every argument after the serialized executable.
func TestWindowsCommandLine_CommandLineToArgvRoundTrip(t *testing.T) {
	want := []string{
		"",
		"safe",
		"contains spaces",
		"contains\ttab",
		`a\"b`,
		`ends with slash\\`,
		`space and slash \\`,
		"雪とemoji 😀",
	}
	args := append([]string{"native-test.exe"}, want...)
	line, err := windows.UTF16PtrFromString(ComposeCommandLine(args))
	if err != nil {
		t.Fatal(err)
	}
	var count int32
	parsed, err := windows.CommandLineToArgv(line, &count)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if _, err := windows.LocalFree(windows.Handle(unsafe.Pointer(parsed))); err != nil {
			t.Errorf("free parsed command line: %v", err)
		}
	}()
	if count != int32(len(args)) {
		t.Fatalf("parsed argument count = %d, want %d", count, len(args))
	}
	got := make([]string, count-1)
	for i := 1; i < int(count); i++ {
		value := parsed[i]
		if value == nil {
			t.Fatalf("argument %d is nil", i)
		}
		end := 0
		for end < len(value) && value[end] != 0 {
			end++
		}
		got[i-1] = string(utf16.Decode(value[:end]))
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("CommandLineToArgvW round trip = %#v, want %#v", got, want)
	}
}

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
