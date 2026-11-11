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

// ValidateJSON validates JSON text without changing its original representation.
func ValidateJSON(data []byte, limits JSONLimits) error {
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
	for i := 0; i+5 < len(data); i++ {
		if data[i] != '\\' || data[i+1] != 'u' {
			continue
		}
		code := hex4(data[i+2 : i+6])
		if code < 0 {
			continue
		}
		if code >= 0xD800 && code <= 0xDBFF {
			i += 5
			if i+6 >= len(data) || data[i+1] != '\\' || data[i+2] != 'u' {
				return true
			}
			next := hex4(data[i+3 : i+7])
			if next < 0xDC00 || next > 0xDFFF {
				return true
			}
			i += 6
		} else if code >= 0xDC00 && code <= 0xDFFF {
			return true
		}
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
