// Package uuid mints and recognizes the canonical identifiers the daemon exchanges: its instance
// identity, resource transfer ids, temporary workspace directories and locally demanded update
// operations.
package uuid

import (
	"crypto/rand"
	"encoding/hex"
)

// New returns a random version 4 UUID (RFC 9562) in canonical lower-case form.
func New() string {
	var id [16]byte
	// crypto/rand.Read never returns an error; it aborts the program if entropy is unavailable.
	_, _ = rand.Read(id[:])
	id[6] = id[6]&0x0f | 0x40
	id[8] = id[8]&0x3f | 0x80
	text := hex.EncodeToString(id[:])
	return text[:8] + "-" + text[8:12] + "-" + text[12:16] + "-" + text[16:20] + "-" + text[20:]
}

// Canonical reports whether value is a lower-case hexadecimal UUID in the 8-4-4-4-12 form.
func Canonical(value string) bool {
	if len(value) != 36 {
		return false
	}
	for index := 0; index < len(value); index++ {
		if index == 8 || index == 13 || index == 18 || index == 23 {
			if value[index] != '-' {
				return false
			}
			continue
		}
		if !lowerHex(value[index]) {
			return false
		}
	}
	return true
}

func lowerHex(char byte) bool {
	return char >= '0' && char <= '9' || char >= 'a' && char <= 'f'
}
