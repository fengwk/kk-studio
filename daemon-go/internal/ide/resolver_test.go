package ide

import (
	"context"
	"fmt"
	"net/http"
	"testing"

	"github.com/fengwk/kk-studio/daemon/internal/transport"
)

// IDE-F-010/IDE-T-135 verifies the frozen route, private-header filter, manager cookie, and WebSocket protocols.
func TestFrozenViewProxyResolution(t *testing.T) {
	resolver := NewResolver()
	resolver.BindView("123e4567-e89b-12d3-a456-426614174000", 1, 8123, "code-server-session=session")
	upstream, err := resolver.ResolveView(context.Background(), transport.ViewRequest{
		ViewID: "123e4567-e89b-12d3-a456-426614174000", BindingEpoch: 1, Mode: "websocket",
		Method: "GET", Path: "/?folder=a", Headers: http.Header{
			"X-Test": {"value"}, "Host": {"attacker.invalid"}, "Connection": {"upgrade"},
			"Keep-Alive": {"timeout=5"}, "Proxy-Authenticate": {"Basic"}, "Proxy-Authorization": {"secret"},
			"Te": {"trailers"}, "Trailer": {"X-End"}, "Transfer-Encoding": {"chunked"}, "Upgrade": {"websocket"},
			"Cookie": {"evil=1"}, "Authorization": {"Bearer evil"}, "Origin": {"https://evil.invalid"},
			"Referer": {"https://evil.invalid/"}, "Sec-Websocket-Key": {"secret"}, "Sec-Websocket-Version": {"13"},
			"Sec-Websocket-Extensions": {"permessage-deflate"}, "Sec-Websocket-Protocol": {"first, second"},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Release()
	if upstream.Host != "127.0.0.1" || upstream.Port != 8123 || upstream.Path != "/__kk/ide/view/123e4567-e89b-12d3-a456-426614174000/1/?folder=a" {
		t.Fatalf("unexpected upstream: %#v", upstream)
	}
	if upstream.Cookie != "code-server-session=session" || upstream.Headers.Get("X-Test") != "value" {
		t.Fatalf("unexpected upstream headers/cookie: %#v", upstream)
	}
	for _, name := range []string{
		"Host", "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "Te", "Trailer",
		"Transfer-Encoding", "Upgrade", "Cookie", "Authorization", "Origin", "Referer", "Sec-Websocket-Key",
		"Sec-Websocket-Version", "Sec-Websocket-Extensions", "Sec-Websocket-Protocol",
	} {
		if got := upstream.Headers.Get(name); got != "" {
			t.Errorf("private header %q forwarded: %q", name, got)
		}
	}
	if len(upstream.Protocols) != 2 || upstream.Protocols[0] != "first" {
		t.Fatalf("protocols = %#v", upstream.Protocols)
	}
}

// IDE-T-135
func TestResolverRejectsUnsafeAndStaleRequests(t *testing.T) {
	resolver := NewResolver()
	viewID := "123e4567-e89b-12d3-a456-426614174000"
	resolver.BindView(viewID, 2, 8123, "")
	for _, request := range []transport.ViewRequest{
		{ViewID: viewID, BindingEpoch: 1, Mode: "http", Method: "GET", Path: "/"},
		{ViewID: viewID, BindingEpoch: 2, Mode: "http", Method: "GET", Path: "/%2e%2e/secret"},
		{ViewID: viewID, BindingEpoch: 2, Mode: "http", Method: "GET", Path: "/", Headers: http.Header{"Bad Name": {"x"}}},
	} {
		if _, err := resolver.ResolveView(context.Background(), request); err == nil {
			t.Errorf("unsafe/stale request accepted: %#v", request)
		}
	}
	resolver.BindView(viewID, 3, 8123, "")
	if _, err := resolver.ResolveView(context.Background(), transport.ViewRequest{ViewID: viewID, BindingEpoch: 2, Mode: "http", Method: "GET", Path: "/"}); err == nil {
		t.Fatal("old epoch resolved after rebind")
	}
}

// IDE-T-088
func TestResolverRejectsInvalidMetadataAndUnboundViews(t *testing.T) {
	resolver := NewResolver()
	viewID := "123e4567-e89b-12d3-a456-426614174000"
	resolver.BindView(viewID, 1, 8123, "")
	invalid := []transport.ViewRequest{
		{ViewID: viewID, BindingEpoch: 1, Mode: "OTHER", Method: "GET", Path: "/"},
		{ViewID: viewID, BindingEpoch: 1, Mode: "http", Method: "G ET", Path: "/"},
		{ViewID: viewID, BindingEpoch: 1, Mode: "http", Method: "GET", Path: "//host/"},
		{ViewID: viewID, BindingEpoch: 1, Mode: "http", Method: "GET", Path: "/a\\b"},
		{ViewID: viewID, BindingEpoch: 1, Mode: "http", Method: "GET", Path: "/%2E%2e/"},
		{ViewID: viewID, BindingEpoch: 1, Mode: "http", Method: "GET", Path: "/", Headers: http.Header{"X-Test": {"bad\rvalue"}}},
	}
	for _, request := range invalid {
		if _, err := resolver.ResolveView(context.Background(), request); err == nil {
			t.Errorf("invalid request accepted: %#v", request)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := resolver.ResolveView(ctx, transport.ViewRequest{ViewID: viewID, BindingEpoch: 1, Mode: "http", Method: "GET", Path: "/"}); err == nil {
		t.Fatal("cancelled request accepted")
	}
	if _, err := resolver.ResolveView(context.Background(), transport.ViewRequest{ViewID: viewID, BindingEpoch: 1, Mode: "http", Method: "GET", Path: "/"}); err != nil {
		t.Fatalf("valid request rejected: %v", err)
	}
	resolver.UnbindView(viewID, 99)
	if _, err := resolver.ResolveView(context.Background(), transport.ViewRequest{ViewID: viewID, BindingEpoch: 1, Mode: "http", Method: "GET", Path: "/"}); err != nil {
		t.Fatalf("stale unbind removed current view: %v", err)
	}
	resolver.UnbindView(viewID, 1)
	if _, err := resolver.ResolveView(context.Background(), transport.ViewRequest{ViewID: viewID, BindingEpoch: 1, Mode: "http", Method: "GET", Path: "/"}); err == nil {
		t.Fatal("unbound view resolved")
	}
	resolver.BindView(viewID, 2, 0, "")
	if _, err := resolver.ResolveView(context.Background(), transport.ViewRequest{ViewID: viewID, BindingEpoch: 2, Mode: "http", Method: "GET", Path: "/"}); err == nil {
		t.Fatal("invalid upstream port accepted")
	}
	resolver.BindView(viewID, 3, 8123, "")
	tooMany := make(http.Header)
	for i := 0; i <= maxStreamHeaders; i++ {
		tooMany[fmt.Sprintf("X-%d", i)] = []string{"v"}
	}
	if _, err := resolver.ResolveView(context.Background(), transport.ViewRequest{ViewID: viewID, BindingEpoch: 3, Mode: "http", Method: "GET", Path: "/", Headers: tooMany}); err == nil {
		t.Fatal("header count limit was not enforced")
	}
}
