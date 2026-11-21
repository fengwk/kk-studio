package runtime

import (
	"context"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// handle feeds one inbound envelope through the same dispatch serveConnection uses, with the
// connection context doubling as the IDE command context.
func (r *Runtime) handle(ctx context.Context, e *protocol.Envelope) error {
	return r.handleWithIDEContext(ctx, ctx, nil, e)
}
