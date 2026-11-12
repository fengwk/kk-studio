package transport

import (
	"context"
	"net/http"
	"testing"
	"time"
)

// TRN-T-032
func TestTunnel_UpstreamContextCancellation(t *testing.T) {
	parent, cancelParent := context.WithCancel(context.Background())
	upstream, cancelUpstream := context.WithCancel(context.Background())
	defer cancelParent()
	defer cancelUpstream()

	ctx, cancel := upstreamContext(parent, upstream)
	defer cancel()
	cancelUpstream()
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("upstream cancellation did not cancel request")
	}
}

// TRN-T-028, TRN-T-029, TRN-T-030, TRN-T-031
func TestTunnel_UpstreamValidation(t *testing.T) {
	base := ViewUpstream{Host: "127.0.0.1", Port: 8080, Path: "/", Headers: http.Header{}, Protocols: []string{"chat"}}
	if err := validateViewUpstream(base); err != nil {
		t.Fatalf("valid upstream rejected: %v", err)
	}
	for i, mutate := range []func(*ViewUpstream){
		func(u *ViewUpstream) { u.Host = "localhost" },
		func(u *ViewUpstream) { u.Host = "::ffff:127.0.0.1" },
		func(u *ViewUpstream) { u.Port = 0 },
		func(u *ViewUpstream) { u.Path = "//host/path" },
		func(u *ViewUpstream) { u.Path = "/bad\x7fpath" },
		func(u *ViewUpstream) { u.Headers.Set("bad name", "x") },
		func(u *ViewUpstream) { u.Headers.Set("X-Test", "bad\x00value") },
		func(u *ViewUpstream) { u.Cookie = "bad\x1fvalue" },
		func(u *ViewUpstream) { u.Protocols = []string{"bad value"} },
	} {
		up := base
		up.Headers = base.Headers.Clone()
		mutate(&up)
		if err := validateViewUpstream(up); err == nil {
			t.Errorf("accepted invalid upstream case %d", i)
		}
	}
}

// TRN-T-034
func TestTunnel_ParseRoute(t *testing.T) {
	const id = "123e4567-e89b-12d3-a456-426614174000"
	for _, tc := range []struct {
		path    string
		ok      bool
		epoch   int64
		subpath string
	}{
		{"/ide/" + id + "/1", true, 1, "/"},
		{"/ide/" + id + "/27/a/b", true, 27, "/a/b"},
		{"/ide/" + id + "/01", false, 0, ""},
		{"/ide/" + id + "/0", false, 0, ""},
		{"/ide/" + id + "/9223372036854775808", false, 0, ""},
		{"/ide/ABC/1", false, 0, ""},
	} {
		gotID, gotEpoch, gotPath, ok := parseIDERoute(tc.path)
		if ok != tc.ok || gotEpoch != tc.epoch || gotPath != tc.subpath || tc.ok && gotID != id {
			t.Errorf("parseIDERoute(%q)=(%q,%d,%q,%t)", tc.path, gotID, gotEpoch, gotPath, ok)
		}
	}
}
