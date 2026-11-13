package transport

import (
	"context"
	"encoding/binary"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
	"google.golang.org/protobuf/proto"
)

// TRN-T-073 / RUN-T-055 a peer GOAWAY frame read from the wire reaches the attempt observer with
// its error code, so the runtime can apply GOAWAY policy to the generation that observed it.
func TestControlConnection_ReportsPeerGoawayFrameToObserver(t *testing.T) {
	for _, code := range []uint32{0, 11} {
		serverConn, clientConn := net.Pipe()
		observed := make(chan uint32, 1)
		connection := newControlConnection(serverConn, nil, recordingObserver{goaway: func(code uint32) { observed <- code }})
		frame := append([]byte(http2ClientPreface), make([]byte, 9)...)
		frame[len(http2ClientPreface)+3] = 4 // Empty client SETTINGS frame.
		goaway := make([]byte, 9+8)
		goaway[2] = 8 // Payload length.
		goaway[3] = 7 // GOAWAY
		binary.BigEndian.PutUint32(goaway[9+4:9+8], code)
		go func() { _, _ = clientConn.Write(append(frame, goaway...)) }()
		select {
		case got := <-observed:
			if got != code {
				t.Fatalf("observed GOAWAY code=%d, want %d", got, code)
			}
		case <-time.After(3 * time.Second):
			t.Fatalf("GOAWAY frame with code %d was not reported to the attempt observer", code)
		}
		_ = connection.Close()
		_ = clientConn.Close()
	}
}

// RUN-T-057 an HTTP 426 rejection with an UpgradeRequired body reports the parsed target version to
// the attempt observer, and a 426 without a parsable body reports the status alone.
func TestDial_ReportsUpgradeRejectionToAttemptObserver(t *testing.T) {
	upgradeBytes, err := proto.Marshal(&protocol.UpgradeRequired{TargetVersion: proto.String("1.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name        string
		body        []byte
		wantVersion string
	}{
		{"target version", upgradeBytes, "1.0.1"},
		{"empty body", nil, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(http.StatusUpgradeRequired)
				_, _ = w.Write(tc.body)
			}))
			defer server.Close()
			reported := make(chan struct {
				status  int
				upgrade *protocol.UpgradeRequired
			}, 1)
			_, dialErr := Dial(context.Background(), DialConfig{
				Applied: config.DaemonConfig{TokenPath: "token", StudioURL: server.URL, EnvironmentID: testEnvironment},
				ReadToken: func(string) (string, error) {
					return "token", nil
				},
				Observer: recordingObserver{upgradeFailed: func(status int, upgrade *protocol.UpgradeRequired) {
					reported <- struct {
						status  int
						upgrade *protocol.UpgradeRequired
					}{status, upgrade}
				}},
			})
			if dialErr == nil {
				t.Fatal("426 handshake unexpectedly succeeded")
			}
			result := <-reported
			if result.status != http.StatusUpgradeRequired {
				t.Fatalf("observed status=%d, want 426", result.status)
			}
			if tc.wantVersion == "" {
				if result.upgrade != nil {
					t.Fatalf("426 without a parsable body reported %v", result.upgrade)
				}
				return
			}
			if result.upgrade == nil || result.upgrade.GetTargetVersion() != tc.wantVersion {
				t.Fatalf("426 target version=%v, want %q", result.upgrade, tc.wantVersion)
			}
		})
	}
}
