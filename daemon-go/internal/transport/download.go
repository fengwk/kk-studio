package transport

import (
	"context"
	"crypto/sha256"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// ResourceDownloader obtains ticketed downloads over the currently applied network policy.
type ResourceDownloader struct {
	Policy *ProxyPolicy
	Client *http.Client
}

// Download consumes a server ticket and verifies bytes and digest before returning them.
func (d *ResourceDownloader) Download(ctx context.Context, ticket string) ([]byte, error) {
	return nil, fmt.Errorf("resource download ticket is required")
}

// DownloadPresigned fetches an explicit presigned GET and verifies its declared metadata.
func (d *ResourceDownloader) DownloadPresigned(ctx context.Context, url string, headers map[string]string, size int64, digest string) ([]byte, error) {
	fail := func(stage string) ([]byte, error) { return nil, fmt.Errorf("resource download %s failed", stage) }
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return fail("request")
	}
	for k, v := range headers {
		if strings.ContainsAny(k+v, "\r\n") {
			return fail("request")
		}
		req.Header.Set(k, v)
	}
	client := d.Client
	if client == nil {
		if d.Policy == nil {
			return fail("network")
		}
		client = d.Policy.HTTPClient()
	}
	resp, err := client.Do(req)
	if err != nil {
		return fail("request")
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fail(fmt.Sprintf("storage status %d", resp.StatusCode))
	}
	var body []byte
	limit := size + 1
	if size < 0 {
		return fail("size")
	}
	body, err = io.ReadAll(io.LimitReader(resp.Body, limit))
	if err != nil {
		return fail("response")
	}
	if int64(len(body)) != size {
		return fail("size")
	}
	sum := sha256.Sum256(body)
	if fmt.Sprintf("%x", sum) != digest {
		return fail("digest")
	}
	return body, nil
}
