package lsp

import (
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
)

// LSP-T-038 keeps an acquired generation alive until its calls release it.
func TestFacade_GenerationSwap(t *testing.T) {
	facade := NewFacade(&fakeSupervisor{}, PoolOptions{})
	prepared, err := facade.Prepare([]config.LSPServerConfig{{ID: "go", Command: []string{"missing"}, Extensions: []string{".go"}}})
	if err != nil {
		t.Fatal(err)
	}
	if err = prepared.Publish(); err != nil {
		t.Fatal(err)
	}
	old := facade.current
	_, release := old.acquire()
	disabled, err := facade.Prepare(nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = disabled.Publish(); err != nil {
		t.Fatal(err)
	}
	if old.service.Pool.closing {
		t.Fatal("old generation closed while a call was still acquired")
	}
	if got := facade.Support("/tmp/a.go"); got.Supported || got.Available || got.Language != nil {
		t.Fatalf("disabled support = %#v", got)
	}
	facade.FileChanged("/tmp/a.go")
	if facade.ActiveClientCount() != 0 {
		t.Fatal("disabled facade reports active clients")
	}
	release()
	select {
	case <-old.done:
	case <-time.After(time.Second):
		t.Fatal("retired generation did not close after its call finished")
	}
	if old.service.Pool.Close() != nil {
		t.Fatal("repeated close returned an error")
	}
	if err = facade.Close(); err != nil {
		t.Fatal(err)
	}
}
