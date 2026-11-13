package transport

import "github.com/fengwk/kk-studio/daemon/internal/protocol"

// recordingObserver captures the transport events a Dial attempt reports for its own connection.
type recordingObserver struct {
	goaway        func(uint32)
	upgradeFailed func(int, *protocol.UpgradeRequired)
}

func (o recordingObserver) NotifyGoaway(code uint32) {
	if o.goaway != nil {
		o.goaway(code)
	}
}

func (o recordingObserver) NotifyUpgradeFailed(statusCode int, upgradeRequired *protocol.UpgradeRequired) {
	if o.upgradeFailed != nil {
		o.upgradeFailed(statusCode, upgradeRequired)
	}
}
