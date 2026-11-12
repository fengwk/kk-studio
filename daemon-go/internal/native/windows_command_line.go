package native

import "strings"

// ComposeCommandLine applies the CreateProcess/MSVC argument quoting rules.
func ComposeCommandLine(args []string) string {
	quoted := make([]string, len(args))
	for i, arg := range args {
		quoted[i] = quoteWindowsArgument(arg)
	}
	return strings.Join(quoted, " ")
}

func quoteWindowsArgument(arg string) string {
	if arg != "" && !strings.ContainsAny(arg, " \t\"") {
		return arg
	}
	var out strings.Builder
	out.WriteByte('"')
	backslashes := 0
	for _, r := range arg {
		if r == '\\' {
			backslashes++
			continue
		}
		if r == '"' {
			out.WriteString(strings.Repeat("\\", backslashes*2+1))
			out.WriteRune(r)
			backslashes = 0
			continue
		}
		out.WriteString(strings.Repeat("\\", backslashes))
		backslashes = 0
		out.WriteRune(r)
	}
	out.WriteString(strings.Repeat("\\", backslashes*2))
	out.WriteByte('"')
	return out.String()
}
