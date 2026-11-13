package transport

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

// TRN-T-059
func TestTunnel_WebSocketHandshakeHeadersAndDuplex(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	serverRequest := make(chan *http.Request, 1)
	serverBytes := make(chan string, 1)
	serverErr := make(chan error, 1)
	serverStop := make(chan struct{})
	defer close(serverStop)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			serverErr <- err
			return
		}
		defer conn.Close()
		request, err := http.ReadRequest(bufio.NewReader(conn))
		if err != nil {
			serverErr <- err
			return
		}
		serverRequest <- request
		if _, err = fmt.Fprint(conn, "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: accept\r\nSec-WebSocket-Protocol: chat\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\nserver"); err != nil {
			serverErr <- err
			return
		}
		got := make([]byte, 4)
		if _, err = io.ReadFull(conn, got); err != nil {
			serverErr <- err
			return
		}
		serverBytes <- string(got)
		<-serverStop
	}()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	clientRead, clientWrite := io.Pipe()
	defer clientWrite.Close()
	responseRead, responseWrite := io.Pipe()
	defer responseRead.Close()
	request := httptest.NewRequest(http.MethodConnect, "http://daemon/ide/123e4567-e89b-12d3-a456-426614174000/1", clientRead).WithContext(ctx)
	request.Header.Set(":protocol", "websocket")
	request.Header.Set("Sec-WebSocket-Key", "client-key")
	request.Header.Set("Sec-WebSocket-Version", "13")
	request.Header.Set("Sec-WebSocket-Protocol", "chat")
	request.Header.Set("Sec-WebSocket-Extensions", "permessage-deflate")
	response := &tunnelResponseWriter{header: make(http.Header), writer: responseWrite}
	responseBytes := make(chan string, 1)
	go func() {
		b := make([]byte, 6)
		_, err := io.ReadFull(responseRead, b)
		if err != nil {
			serverErr <- err
			return
		}
		responseBytes <- string(b)
	}()
	addr, portText, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, _ := strconv.Atoi(portText)
	upstream := ViewUpstream{Host: addr, Port: port, Path: "/", Protocols: []string{"chat"}}
	done := make(chan any, 1)
	go func() {
		defer func() { done <- recover() }()
		(&ControlConnection{}).tunnelIDEWebSocket(response, request, upstream)
	}()
	select {
	case got := <-serverRequest:
		for name, want := range map[string]string{"Sec-WebSocket-Key": "client-key", "Sec-WebSocket-Version": "13", "Sec-WebSocket-Protocol": "chat", "Sec-WebSocket-Extensions": "permessage-deflate"} {
			if got.Header.Get(name) != want {
				t.Errorf("upstream %s=%q want %q", name, got.Header.Get(name), want)
			}
		}
	case err := <-serverErr:
		t.Fatal(err)
	case <-time.After(time.Second):
		t.Fatal("upstream handshake was not received")
	}
	if _, err = io.WriteString(clientWrite, "ping"); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-serverBytes:
		if got != "ping" {
			t.Fatalf("upstream bytes=%q", got)
		}
	case err := <-serverErr:
		t.Fatal(err)
	case <-time.After(time.Second):
		t.Fatal("client bytes were not forwarded")
	}
	select {
	case got := <-responseBytes:
		if got != "server" {
			t.Fatalf("downstream bytes=%q", got)
		}
	case err := <-serverErr:
		t.Fatal(err)
	case <-time.After(time.Second):
		t.Fatal("upstream bytes were not forwarded")
	}
	cancel()
	select {
	case recovered := <-done:
		if recovered != nil {
			t.Fatalf("unexpected tunnel panic after cancellation: %v", recovered)
		}
	case <-time.After(time.Second):
		t.Fatal("cancel did not end websocket tunnel")
	}
}

type tunnelResponseWriter struct {
	header http.Header
	writer io.Writer
	status int
}

func (w *tunnelResponseWriter) Header() http.Header         { return w.header }
func (w *tunnelResponseWriter) WriteHeader(status int)      { w.status = status }
func (w *tunnelResponseWriter) Write(p []byte) (int, error) { return w.writer.Write(p) }
func (*tunnelResponseWriter) Flush()                        {}
