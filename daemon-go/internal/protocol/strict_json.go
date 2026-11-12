package protocol

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"unicode/utf8"
)

// JSONLimits configures optional strict JSON limits. A zero limit is unlimited.
type JSONLimits struct{ MaxBytes, MaxNestingDepth, MaxNumberLength int }

// UTF8ByteLength returns the byte length of valid UTF-8 text.
func UTF8ByteLength(value string) (int, error) {
	if !utf8.ValidString(value) {
		return 0, fmt.Errorf("must be valid Unicode")
	}
	return len(value), nil
}

// ValidateJSON validates JSON text without changing its original representation.
func ValidateJSON(input any, limits JSONLimits) error {
	var data []byte
	switch value := input.(type) {
	case string:
		data = []byte(value)
	case []byte:
		data = value
	default:
		return fmt.Errorf("must be a string")
	}
	if limits.MaxBytes > 0 && len(data) > limits.MaxBytes {
		return fmt.Errorf("exceeds %d UTF-8 bytes", limits.MaxBytes)
	}
	if !utf8.Valid(data) || hasUnpairedSurrogate(data) {
		return fmt.Errorf("must be valid JSON")
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	if err := strictValue(dec, limits, 0); err != nil {
		return err
	}
	if _, err := dec.Token(); err != io.EOF {
		return fmt.Errorf("must be valid JSON")
	}
	return nil
}

func strictValue(dec *json.Decoder, limits JSONLimits, depth int) error {
	tok, err := dec.Token()
	if err != nil {
		return fmt.Errorf("must be valid JSON")
	}
	switch v := tok.(type) {
	case json.Delim:
		if v == '{' || v == '[' {
			depth++
			if limits.MaxNestingDepth > 0 && depth > limits.MaxNestingDepth {
				return fmt.Errorf("exceeds maximum nesting depth %d", limits.MaxNestingDepth)
			}
			if v == '{' {
				seen := make(map[string]struct{})
				for dec.More() {
					key, err := dec.Token()
					if err != nil {
						return fmt.Errorf("must be valid JSON")
					}
					name, ok := key.(string)
					if !ok {
						return fmt.Errorf("must be valid JSON")
					}
					if _, ok := seen[name]; ok {
						return fmt.Errorf("contains duplicate field: %s", name)
					}
					seen[name] = struct{}{}
					if err := strictValue(dec, limits, depth); err != nil {
						return err
					}
				}
				if _, err := dec.Token(); err != nil {
					return fmt.Errorf("must be valid JSON")
				}
				return nil
			}
			for dec.More() {
				if err := strictValue(dec, limits, depth); err != nil {
					return err
				}
			}
			if _, err := dec.Token(); err != nil {
				return fmt.Errorf("must be valid JSON")
			}
			return nil
		}
		return fmt.Errorf("must be valid JSON")
	case json.Number:
		if limits.MaxNumberLength > 0 && len(v.String()) > limits.MaxNumberLength {
			return fmt.Errorf("number exceeds %d characters", limits.MaxNumberLength)
		}
	}
	return nil
}

func hasUnpairedSurrogate(data []byte) bool {
	for i := 0; i < len(data); i++ {
		if data[i] != '\\' {
			continue
		}
		if i+1 >= len(data) {
			return false
		}
		if data[i+1] != 'u' {
			i++
			continue
		}
		if i+5 >= len(data) {
			continue
		}
		code := hex4(data[i+2 : i+6])
		if code < 0 {
			continue
		}
		if code >= 0xD800 && code <= 0xDBFF {
			if i+11 >= len(data) || data[i+6] != '\\' || data[i+7] != 'u' {
				return true
			}
			next := hex4(data[i+8 : i+12])
			if next < 0xDC00 || next > 0xDFFF {
				return true
			}
			i += 11
			continue
		} else if code >= 0xDC00 && code <= 0xDFFF {
			return true
		}
		i += 5
	}
	return false
}

func hex4(b []byte) int {
	if len(b) < 4 {
		return -1
	}
	v := 0
	for _, c := range b[:4] {
		v <<= 4
		switch {
		case c >= '0' && c <= '9':
			v += int(c - '0')
		case c >= 'a' && c <= 'f':
			v += int(c-'a') + 10
		case c >= 'A' && c <= 'F':
			v += int(c-'A') + 10
		default:
			return -1
		}
	}
	return v
}
