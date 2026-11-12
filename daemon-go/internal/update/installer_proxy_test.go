package update

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// RUN-T-205 uses the applied proxy URL for installer requests and never inherits HTTP_PROXY when absent.
func TestClientForProxySelectsConfiguredProxyOrDirect(t *testing.T) {
	proxyRequests := make(chan string, 2)
	proxy := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		proxyRequests <- request.URL.Host
		writer.WriteHeader(http.StatusOK)
	}))
	defer proxy.Close()

	proxyURL := proxy.URL
	client, err := clientForProxy(&http.Client{Timeout: time.Second}, &proxyURL)
	if err != nil {
		t.Fatal(err)
	}
	request, err := http.NewRequestWithContext(context.Background(), http.MethodGet, "http://updates.example/install.sh", nil)
	if err != nil {
		t.Fatal(err)
	}
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("request through applied proxy: %v", err)
	}
	response.Body.Close()
	select {
	case host := <-proxyRequests:
		if host != "updates.example" {
			t.Fatalf("proxy received target host %q", host)
		}
	case <-time.After(time.Second):
		t.Fatal("configured proxy did not receive request")
	}
	client.CloseIdleConnections()

	withoutProxy, err := clientForProxy(&http.Client{Timeout: time.Second}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if transport, ok := withoutProxy.Transport.(*http.Transport); !ok || transport.Proxy != nil {
		t.Fatalf("no configured proxy did not force direct transport: %#v", withoutProxy.Transport)
	}
}

// RUN-T-205 rejects malformed proxy settings and custom transports that cannot enforce the configured proxy.
func TestClientForProxyRejectsInvalidOrUnenforceableProxy(t *testing.T) {
	for _, value := range []string{"https://proxy.example:8080", "http://proxy.example", "http://user:pass@proxy.example:8080", "http://proxy.example:8080/path", "http://proxy.example:8080?", "http://proxy.example:8080#", "http://proxy.example:0"} {
		t.Run(value, func(t *testing.T) {
			if _, err := clientForProxy(&http.Client{}, &value); err == nil {
				t.Fatalf("invalid proxy %q accepted", value)
			}
		})
	}
	value := "http://proxy.example:8080"
	custom := &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) { return nil, nil })}
	if _, err := clientForProxy(custom, &value); err == nil {
		t.Fatal("configured proxy with custom RoundTripper was accepted")
	}
	if _, err := clientForProxy(nil, nil); err == nil {
		t.Fatal("nil client was accepted")
	}
}

// RUN-T-205 ensures inherited proxy variables are not consulted when configuration explicitly selects direct access.
func TestClientForProxyDirectIgnoresEnvironmentProxy(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	listener.Close()
	proxyURL := "http://" + listener.Addr().String()
	t.Setenv("HTTP_PROXY", proxyURL)
	t.Setenv("http_proxy", proxyURL)
	client, err := clientForProxy(&http.Client{Timeout: 150 * time.Millisecond}, nil)
	if err != nil {
		t.Fatal(err)
	}
	transport, ok := client.Transport.(*http.Transport)
	if !ok || transport.Proxy != nil {
		t.Fatalf("direct transport consulted proxy environment: %#v", client.Transport)
	}
}
