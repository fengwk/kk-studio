package native

import (
	"bytes"
	"errors"
	"io"
	"testing"
)

type limitedWriter struct {
	limit int
	write []byte
	err   error
}

func (w *limitedWriter) Write(data []byte) (int, error) {
	if w.err != nil {
		return 0, w.err
	}
	if w.limit == 0 {
		return 0, nil
	}
	n := len(data)
	if n > w.limit {
		n = w.limit
	}
	w.write = append(w.write, data[:n]...)
	return n, nil
}

// Short writes must be retried until all private-file bytes are committed.
func TestWriteAll_RetriesShortWrites(t *testing.T) {
	want := []byte("sensitive payload")
	writer := &limitedWriter{limit: 2}
	if err := writeAll(writer, want); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(writer.write, want) {
		t.Fatalf("written bytes = %q, want %q", writer.write, want)
	}
}

// A writer that makes no progress or fails must stop publication with its error.
func TestWriteAll_FailsOnNoProgressOrWriterError(t *testing.T) {
	if err := writeAll(&limitedWriter{}, []byte("payload")); !errors.Is(err, io.ErrShortWrite) {
		t.Fatalf("zero-progress error = %v, want %v", err, io.ErrShortWrite)
	}
	writeErr := errors.New("disk write failed")
	if err := writeAll(&limitedWriter{err: writeErr}, []byte("payload")); !errors.Is(err, writeErr) {
		t.Fatalf("writer error = %v, want %v", err, writeErr)
	}
	writer := &limitedWriter{}
	if err := writeAll(writer, nil); err != nil || len(writer.write) != 0 {
		t.Fatalf("empty payload result = %v, written %q", err, writer.write)
	}
}
