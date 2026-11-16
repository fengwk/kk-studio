package protocol

import (
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf16"
)

const maxSafeInteger int64 = 1<<53 - 1

var (
	versionPattern    = regexp.MustCompile(`^[0-9A-Za-z._+-]{1,64}$`)
	capabilityPattern = regexp.MustCompile(`^[a-z0-9]+(?:[.-][a-z0-9]+)*$`)
	mediaTypePattern  = regexp.MustCompile(`^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$`)
	ideCodePattern    = regexp.MustCompile(`^[A-Z][A-Z0-9_]{0,63}$`)
	proxyURLPattern   = regexp.MustCompile(`(?i)^http://(\[[^\]]+\]|[^/?#@\[\]:]+):([0-9]{1,5})$`)
	serverIDPattern   = regexp.MustCompile(`^[A-Za-z0-9_.-]+$`)
	fieldPathPattern  = regexp.MustCompile(`^[A-Za-z0-9_.\[\]-]+$`)
)

func invalid(s string) error   { return fmt.Errorf("%s", s) }
func utf16Length(s string) int { return len(utf16.Encode([]rune(s))) }

func validProxyURL(value string) bool {
	if utf16Length(value) > 2048 {
		return false
	}
	match := proxyURLPattern.FindStringSubmatch(value)
	if match == nil {
		return false
	}
	port, err := strconv.Atoi(match[2])
	if err != nil || port < 1 || port > 65535 {
		return false
	}
	host := strings.Trim(match[1], "[]")
	return host != "*"
}

func requireString(value *string, name string, nonblank bool, max int) error {
	if value == nil {
		return invalid(name + " must be a string")
	}
	s := *value
	if nonblank && strings.TrimSpace(s) == "" {
		return invalid(name + " must not be blank")
	}
	if max > 0 && utf16Length(s) > max {
		return invalid(fmt.Sprintf("%s exceeds %d characters", name, max))
	}
	return nil
}

func requireBytesString(value *string, name string, nonblank bool, max int) error {
	if value == nil {
		return invalid(name + " must be a string")
	}
	if nonblank && strings.TrimSpace(*value) == "" {
		return invalid(name + " must not be blank")
	}
	if max > 0 && len(*value) > max {
		return invalid(fmt.Sprintf("%s exceeds %d UTF-8 bytes", name, max))
	}
	return nil
}

func uuid(s, name string) error {
	if !canonicalUUID.MatchString(s) {
		return invalid(name + " must be a canonical lowercase UUID")
	}
	return nil
}

func sha(s, name string) error {
	if len(s) != 64 {
		return invalid(name + " must be a lowercase SHA-256")
	}
	for _, c := range s {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return invalid(name + " must be a lowercase SHA-256")
		}
	}
	return nil
}

func mediaType(s, name string) error {
	if len(s) > 255 || !mediaTypePattern.MatchString(s) {
		return invalid(name + " has invalid syntax")
	}
	return nil
}

func daemonVersion(s, name string) error {
	if !versionPattern.MatchString(s) {
		return invalid(name + " has invalid syntax")
	}
	return nil
}

func jsonField(s, name string, max int, object bool) error {
	if s == "" {
		return invalid(name + " is required")
	}
	if err := ValidateJSON([]byte(s), JSONLimits{MaxBytes: max}); err != nil {
		return invalid(name + " " + err.Error())
	}
	if object {
		var first byte
		for i := 0; i < len(s); i++ {
			if s[i] != ' ' && s[i] != '\n' && s[i] != '\r' && s[i] != '\t' {
				first = s[i]
				break
			}
		}
		if first != '{' {
			return invalid(name + " must be a JSON object")
		}
	}
	return nil
}

func hasControl(s string) bool {
	for _, r := range s {
		if unicode.IsControl(r) {
			return true
		}
	}
	return false
}
