package protocol

import (
	"bytes"
	"encoding/binary"
	"testing"
)

// TRN-T-156
func TestFrame_BoundsAndRoundTrip(t *testing.T) {
	for _, payload := range [][]byte{nil, bytes.Repeat([]byte{1}, MaxFrameSize+1)} {
		if _, err := EncodeFrame(payload); err == nil {
			t.Fatalf("EncodeFrame accepted %d bytes", len(payload))
		}
	}
	payload := []byte{1, 2, 3}
	frame, err := EncodeFrame(payload)
	if err != nil {
		t.Fatal(err)
	}
	if got := binary.BigEndian.Uint32(frame[:4]); got != 3 {
		t.Fatalf("length=%d", got)
	}
	decoded, err := DecodeFrame(frame)
	if err != nil || !bytes.Equal(decoded, payload) {
		t.Fatalf("decode=%v err=%v", decoded, err)
	}
	for _, bad := range [][]byte{frame[:3], append(frame, 0), {0, 0, 0, 0}, {0, 0, 0, 1}} {
		if _, err := DecodeFrame(bad); err == nil {
			t.Fatalf("DecodeFrame accepted %x", bad)
		}
	}
}
