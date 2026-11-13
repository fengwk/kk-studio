package h2xconnect

import (
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"

	"go.uber.org/goleak"
	"golang.org/x/net/http2"
)

const settingsProbeEnv = "KK_H2XCONNECT_SETTINGS_PROBE"

type oneConnListener struct {
	conn     net.Conn
	done     chan struct{}
	accepted bool
	once     sync.Once
}

func (l *oneConnListener) Accept() (net.Conn, error) {
	if !l.accepted {
		l.accepted = true
		return l.conn, nil
	}
	<-l.done
	return nil, net.ErrClosed
}

func (l *oneConnListener) Close() error {
	l.once.Do(func() { close(l.done) })
	return l.conn.Close()
}

func (l *oneConnListener) Addr() net.Addr { return l.conn.LocalAddr() }

// TestStdlibHTTP2ServerSendsExtendedConnectSetting checks the real server SETTINGS frame
// in a child process because GODEBUG is read before package init functions run.
func TestStdlibHTTP2ServerSendsExtendedConnectSetting(t *testing.T) {
	if mode := os.Getenv(settingsProbeEnv); mode != "" {
		want := uint32(0)
		if mode == "startup" {
			want = 1
		}
		runExtendedConnectSettingsProbe(t, want)
		return
	}

	for _, mode := range []string{"init-only", "startup"} {
		cmd := exec.Command(os.Args[0], "-test.run=^TestStdlibHTTP2ServerSendsExtendedConnectSetting$")
		for _, value := range os.Environ() {
			if !strings.HasPrefix(value, "GODEBUG=") && !strings.HasPrefix(value, settingsProbeEnv+"=") {
				cmd.Env = append(cmd.Env, value)
			}
		}
		if mode == "startup" {
			cmd.Env = append(cmd.Env, "GODEBUG="+enableExtendedConnect(OriginalGODEBUG()))
		}
		cmd.Env = append(cmd.Env, settingsProbeEnv+"="+mode)
		output, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("HTTP/2 SETTINGS probe (%s) failed: %v: %s", mode, err, output)
		}
	}
}

func runExtendedConnectSettingsProbe(t *testing.T, wantConnectProtocol uint32) {
	defer goleak.VerifyNone(t, goleak.IgnoreCurrent())

	serverConn, clientConn := net.Pipe()
	listener := &oneConnListener{conn: serverConn, done: make(chan struct{})}
	server := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) }),
		HTTP2: &http.HTTP2Config{
			MaxConcurrentStreams:      512,
			MaxReceiveBufferPerStream: 1 << 20,
			MaxReadFrameSize:          16384,
		},
	}
	server.Protocols = new(http.Protocols)
	server.Protocols.SetUnencryptedHTTP2(true)
	serveDone := make(chan struct{})
	go func() {
		defer close(serveDone)
		_ = server.Serve(listener)
	}()
	defer func() {
		_ = server.Close()
		_ = listener.Close()
		_ = clientConn.Close()
		select {
		case <-serveDone:
		case <-time.After(time.Second):
			t.Error("HTTP server did not stop")
		}
	}()

	if err := clientConn.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	framer := http2.NewFramer(clientConn, clientConn)
	if _, err := clientConn.Write([]byte(http2.ClientPreface)); err != nil {
		t.Fatal(err)
	}
	if err := framer.WriteSettings(); err != nil {
		t.Fatal(err)
	}
	for {
		frame, err := framer.ReadFrame()
		if err != nil {
			t.Fatal(err)
		}
		settings, ok := frame.(*http2.SettingsFrame)
		if !ok {
			continue
		}
		values := map[http2.SettingID]uint32{}
		if err := settings.ForeachSetting(func(setting http2.Setting) error {
			values[setting.ID] = setting.Val
			return nil
		}); err != nil {
			t.Fatal(err)
		}
		if values[http2.SettingEnableConnectProtocol] != wantConnectProtocol {
			t.Fatalf("ENABLE_CONNECT_PROTOCOL = %d, want %d; SETTINGS=%v", values[http2.SettingEnableConnectProtocol], wantConnectProtocol, values)
		}
		if values[http2.SettingMaxConcurrentStreams] != 512 || values[http2.SettingInitialWindowSize] != 1<<20 || values[http2.SettingMaxFrameSize] != 16384 {
			t.Fatalf("server SETTINGS = %v", values)
		}
		return
	}
}
