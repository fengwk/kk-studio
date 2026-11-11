package protocol

import (
	"encoding/binary"
	"fmt"
	"io"
)

const MaxFrameSize = 16 << 20

// EncodeFrame encodes one non-empty control envelope payload with its wire prefix.
func EncodeFrame(payload []byte) ([]byte, error) {
	if len(payload) < 1 || len(payload) > MaxFrameSize {
		return nil, fmt.Errorf("control frame length must be in 1..%d bytes", MaxFrameSize)
	}
	frame := make([]byte, 4+len(payload))
	binary.BigEndian.PutUint32(frame, uint32(len(payload)))
	copy(frame[4:], payload)
	return frame, nil
}

// DecodeFrame accepts exactly one complete control frame.
func DecodeFrame(frame []byte) ([]byte, error) {
	if len(frame) < 4 {
		return nil, fmt.Errorf("incomplete control frame prefix")
	}
	n := binary.BigEndian.Uint32(frame[:4])
	if n < 1 || n > MaxFrameSize {
		return nil, fmt.Errorf("invalid control frame length %d", n)
	}
	if uint64(len(frame)) != uint64(n)+4 {
		return nil, fmt.Errorf("control frame length mismatch")
	}
	return frame[4:], nil
}

// ReadFrame reads one frame without buffering beyond its declared payload.
func ReadFrame(r io.Reader) ([]byte, error) {
	var prefix [4]byte
	if _, err := io.ReadFull(r, prefix[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(prefix[:])
	if n < 1 || n > MaxFrameSize {
		return nil, fmt.Errorf("invalid control frame length %d", n)
	}
	payload := make([]byte, int(n))
	if _, err := io.ReadFull(r, payload); err != nil {
		return nil, err
	}
	return payload, nil
}

// WriteFrame writes a single length-prefixed payload.
func WriteFrame(w io.Writer, payload []byte) error {
	frame, err := EncodeFrame(payload)
	if err != nil {
		return err
	}
	for len(frame) > 0 {
		n, err := w.Write(frame)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		frame = frame[n:]
	}
	return nil
}
