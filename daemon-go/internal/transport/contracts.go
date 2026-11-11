package transport

import (
	"context"
	"net/http"
)

// ViewRequest is the parsed identity and request metadata for an IDE data-channel route.
type ViewRequest struct {
	ViewID       string
	BindingEpoch int64
	Mode         string
	Method       string
	Path         string
	Headers      http.Header
}

// ViewUpstream is a validated loopback destination returned by the authoritative IDE service.
type ViewUpstream struct {
	Host      string
	Port      int
	Path      string
	Headers   http.Header
	Cookie    string
	Protocols []string
	Release   func()
}

// ViewResolver is consumed by transport and implemented by IDE. It is the only route
// from an externally supplied view identifier to a local IDE upstream.
type ViewResolver interface {
	ResolveView(context.Context, ViewRequest) (ViewUpstream, error)
}
