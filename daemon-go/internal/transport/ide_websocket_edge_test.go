package transport

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"testing"
)

type tunnelTestConn struct {
	reader   io.Reader
	writeErr error
}

func (c *tunnelTestConn) Read(p []byte) (int, error) { return c.reader.Read(p) }
func (c *tunnelTestConn) Write([]byte) (int, error)  { return 0, c.writeErr }
func (*tunnelTestConn) Close() error                 { return nil }

type recordedHTTPWriter struct {
	header http.Header
	status int
	body   bytes.Buffer
}

func (w *recordedHTTPWriter) Header() http.Header         { return w.header }
func (w *recordedHTTPWriter) WriteHeader(status int)      { w.status = status }
func (w *recordedHTTPWriter) Write(p []byte) (int, error) { return w.body.Write(p) }

// TRN-T-059
func TestTunnel_WebSocketRejectedHandshakeAndUpstreamFailures(t *testing.T) {
	for _, tc := range []struct {
		name, response string
		wantStatus     int
		wantBody       string
		wantHeader     string
	}{
		{"unauthorized", "HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Type: text/plain\r\nSet-Cookie: denied=yes\r\nX-Private: hidden\r\nContent-Length: 6\r\n\r\ndenied", http.StatusUnauthorized, "denied", "denied=yes"},
		{"malformed response", "not an HTTP response\r\n\r\n", http.StatusBadGateway, "IDE upstream unavailable\n", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			go func() {
				conn, e := listener.Accept()
				if e != nil {
					return
				}
				defer conn.Close()
				_, _ = http.ReadRequest(bufio.NewReader(conn))
				_, _ = fmt.Fprint(conn, tc.response)
			}()
			host, portText, _ := net.SplitHostPort(listener.Addr().String())
			port, _ := strconv.Atoi(portText)
			writer := &recordedHTTPWriter{header: make(http.Header)}
			request := &http.Request{Method: http.MethodConnect, URL: &url.URL{Path: "/ws?ignored"}, Header: make(http.Header), Body: http.NoBody}
			(&ControlConnection{}).tunnelIDEWebSocket(writer, request, ViewUpstream{Host: host, Port: port, Path: "/base"}, "/socket")
			if writer.status != tc.wantStatus || writer.body.String() != tc.wantBody || writer.header.Get("Set-Cookie") != tc.wantHeader {
				t.Fatalf("response status=%d headers=%v body=%q", writer.status, writer.header, writer.body.String())
			}
			if tc.wantStatus == http.StatusUnauthorized && writer.header.Get("X-Private") != "" {
				t.Fatal("unfiltered error header forwarded")
			}
		})
	}
	closed, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	host, portText, _ := net.SplitHostPort(closed.Addr().String())
	closed.Close()
	port, _ := strconv.Atoi(portText)
	writer := &recordedHTTPWriter{header: make(http.Header)}
	(&ControlConnection{}).tunnelIDEWebSocket(writer, &http.Request{Method: http.MethodConnect, URL: &url.URL{Path: "/"}, Header: make(http.Header), Body: http.NoBody}, ViewUpstream{Host: host, Port: port, Path: "/"}, "/")
	if writer.status != http.StatusBadGateway {
		t.Fatalf("unavailable upstream status=%d", writer.status)
	}
}

// TRN-T-060, TRN-T-061 verify a failed downstream write is returned so the HTTP/2 handler can reset its stream.
func TestBridgeDuplex_ReturnsStreamWriteFailure(t *testing.T) {
	want := errors.New("stream write failed")
	client := &tunnelTestConn{reader: strings.NewReader(""), writeErr: want}
	upstream := &tunnelTestConn{reader: strings.NewReader("response")}
	if err := bridgeDuplex(t.Context(), client, upstream); !errors.Is(err, want) {
		t.Fatalf("bridge error=%v, want %v", err, want)
	}
}

// TRN-T-040 verifies an upstream body failure after response headers aborts the HTTP/2 stream.
func TestTunnel_HTTPBodyFailureAfterHeadersAbortsStream(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		_, _ = http.ReadRequest(bufio.NewReader(conn))
		_, _ = fmt.Fprint(conn, "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort")
	}()
	host, portText, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	writer := &recordedHTTPWriter{header: make(http.Header)}
	request := &http.Request{Method: http.MethodGet, URL: &url.URL{RawQuery: ""}, Header: make(http.Header), Body: http.NoBody}
	defer func() {
		if recovered := recover(); recovered != http.ErrAbortHandler {
			t.Fatalf("panic=%v, want http.ErrAbortHandler", recovered)
		}
	}()
	(&ControlConnection{}).proxyIDEHTTP(writer, request, ViewUpstream{Host: host, Port: port, Path: "/"}, "/")
}

// TRN-T-059
func TestResponseBodyWriterCloseHandlesNonClosableReader(t *testing.T) {
	var output bytes.Buffer
	writer := responseBodyWriter{reader: strings.NewReader("in"), writer: &output}
	if _, err := writer.Write([]byte("out")); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if output.String() != "out" {
		t.Fatalf("output=%q", output.String())
	}
}
