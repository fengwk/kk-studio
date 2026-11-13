// Package uuid mints the random identifiers the daemon creates itself: its instance identity,
// resource transfer ids and locally demanded update operations.
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
