package runtime

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

// RUN-T-006 maps each supported protocol tier to its capability contract value.
func TestMapImageInputTier(t *testing.T) {
	for _, test := range []struct {
		wire protocol.ImageInputTier
		want ImageInputTier
	}{
		{wire: protocol.ImageInputTier_P720, want: ImageInputTierP720},
		{wire: protocol.ImageInputTier_P1080, want: ImageInputTierP1080},
		{wire: protocol.ImageInputTier_ORIGINAL, want: ImageInputTierOriginal},
	} {
		got, err := mapImageInputTier(&test.wire)
		if err != nil || got != test.want {
			t.Fatalf("mapImageInputTier(%v) = %q, %v; want %q", test.wire, got, err, test.want)
		}
	}
}

// RUN-T-006 rejects missing, unspecified, or unsupported image-input tiers before invoking a capability.
func TestRuntimeRequiresSupportedImageInputTier(t *testing.T) {
	unspecified := protocol.ImageInputTier_IMAGE_INPUT_TIER_UNSPECIFIED
	unsupported := protocol.ImageInputTier(99)
	for _, test := range []struct {
		name    string
		tier    *protocol.ImageInputTier
		message string
	}{
		{name: "missing", message: "imageInputTier is required"},
		{name: "unspecified", tier: &unspecified, message: "imageInputTier is required"},
		{name: "unsupported", tier: &unsupported, message: "imageInputTier is invalid"},
	} {
		t.Run(test.name, func(t *testing.T) {
			connection := newTestConnection()
			queueHandshake(connection)
			envelope := invocationEnvelope()
			envelope.GetInvoke().ImageInputTier = test.tier
			connection.in <- envelope
			var executed atomic.Bool
			runtime := newInvocationRuntime(t, connection, func(context.Context, CapabilityCall, CapabilityContext) (CapabilityResult, error) {
				executed.Store(true)
				return CapabilityResult{}, nil
			})
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() { done <- runtime.Run(ctx) }()
			connection.waitFor(t, func(frames []*protocol.Envelope) bool {
				for _, frame := range frames {
					if frame.MessageType == protocol.MessageType_FAILED && frame.GetFailed().GetMessage() == test.message {
						return true
					}
				}
				return false
			})
			if executed.Load() {
				cancel()
				t.Fatal("capability was invoked with invalid image input tier")
			}
			cancel()
			select {
			case err := <-done:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(time.Second):
				t.Fatal("runtime did not stop")
			}
		})
	}
}
