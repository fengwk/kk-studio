package transport

import (
	"encoding/binary"
	"testing"
)

// TRN-T-073, TRN-T-085
func TestH2FrameObserver_GoawayCodesAcrossReadBoundaries(t *testing.T) {
	for _, code := range []uint32{0, 2} {
		observer := new(h2FrameObserver)
		frame := make([]byte, len(http2ClientPreface)+17)
		copy(frame, http2ClientPreface)
		header := frame[len(http2ClientPreface):]
		header[2] = 8
		header[3] = 7
		binary.BigEndian.PutUint32(header[9+4:9+8], code)
		var got []uint32
		observer.feed(frame[:len(http2ClientPreface)+4], func(value uint32) { got = append(got, value) })
		observer.feed(frame[len(http2ClientPreface)+4:], func(value uint32) { got = append(got, value) })
		if len(got) != 1 || got[0] != code {
			t.Fatalf("GOAWAY codes=%v, want [%d]", got, code)
		}
	}
}
