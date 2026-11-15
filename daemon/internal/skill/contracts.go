package skill

import "context"

// DownloadTicket is the transport-validated metadata needed to retrieve one resource.
// Opaque carries transport-specific ticket data and must be returned unchanged to Download.
type DownloadTicket struct {
	Size   int64
	SHA256 string
	Opaque any
}

// ResourceDownloader separates ticket acquisition from archive transfer so the installer
// can reuse a content-addressed installation without downloading the resource bytes.
type ResourceDownloader interface {
	RequestTicket(context.Context, string) (DownloadTicket, error)
	Download(context.Context, DownloadTicket) ([]byte, error)
}
