package skill

import "context"

// ResourceDownloader fetches the ticketed resource and verifies its declared size and digest.
// Transport implements this capability; skill consumes it for archive installation.
type ResourceDownloader interface {
	Download(context.Context, string) ([]byte, error)
}
