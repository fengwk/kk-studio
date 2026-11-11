package skill

import "context"

// ResourceDownloader fetches the ticketed resource and verifies its declared size and digest.
// Transport implements this capability; skill consumes it for archive installation.
type ResourceDownloader interface {
	Download(context.Context, string) ([]byte, error)
}

// ResourceDigestProvider exposes the verified ticket digest before archive transfer so an
// already-installed matching package can be reused without downloading its bytes again.
type ResourceDigestProvider interface {
	TicketSHA256(context.Context, string) (string, error)
}
