package runtime

import (
	"context"
	"errors"
	"fmt"
	"time"

	"google.golang.org/protobuf/proto"

	"github.com/fengwk/kk-studio/daemon/internal/config"
	"github.com/fengwk/kk-studio/daemon/internal/protocol"
)

func (r *Runtime) handleSettings(e *protocol.Envelope) error {
	if e.EnvironmentId == nil || *e.EnvironmentId == "" || *e.EnvironmentId != r.options.EnvironmentID {
		return errors.New("settings environmentId is missing or does not match")
	}
	if e.Payload == nil {
		return errors.New("settings.resourceBudgets is required")
	}
	settings, ok := e.Payload.(*protocol.Envelope_Settings)
	if !ok || settings.Settings == nil || settings.Settings.ResourceBudgets == nil {
		return errors.New("settings.resourceBudgets is required")
	}
	budgets := settings.Settings.ResourceBudgets
	if (budgets.TemporaryResourceTtlSeconds != nil && budgets.GetTemporaryResourceTtlSeconds() < 0) || (budgets.TemporaryResourceCleanupIntervalSeconds != nil && budgets.GetTemporaryResourceCleanupIntervalSeconds() < 0) {
		return errors.New("settings.resourceBudgets is invalid")
	}
	ttl, interval := time.Duration(budgets.GetTemporaryResourceTtlSeconds())*time.Second, time.Duration(budgets.GetTemporaryResourceCleanupIntervalSeconds())*time.Second
	r.setTemporaryResourcePolicy(ttl, interval, false)
	if err := r.gate.Bind(r.options.EnvironmentID); err != nil {
		return err
	}
	r.mu.Lock()
	r.bound = true
	r.ready = false
	r.mu.Unlock()
	return nil
}

func (r *Runtime) handleTemporaryResourcePolicy(e *protocol.Envelope) error {
	payload, ok := e.Payload.(*protocol.Envelope_TemporaryResourcePolicy)
	if !ok || payload.TemporaryResourcePolicy == nil {
		return r.sendBoundError(e.InvocationId, "temporary resource policy is invalid")
	}
	policy := payload.TemporaryResourcePolicy
	if policy.TemporaryResourceTtlSeconds == nil || policy.TemporaryResourceCleanupIntervalSeconds == nil || policy.GetTemporaryResourceTtlSeconds() <= 0 || policy.GetTemporaryResourceCleanupIntervalSeconds() <= 0 {
		return r.sendBoundError(e.InvocationId, "temporary resource policy is invalid")
	}
	r.setTemporaryResourcePolicy(time.Duration(policy.GetTemporaryResourceTtlSeconds())*time.Second, time.Duration(policy.GetTemporaryResourceCleanupIntervalSeconds())*time.Second, true)
	return nil
}

func (r *Runtime) handleEnvironmentConfig(ctx context.Context, e *protocol.Envelope) error {
	payload, ok := e.Payload.(*protocol.Envelope_EnvironmentConfig)
	if !ok || payload.EnvironmentConfig == nil || payload.EnvironmentConfig.Config == nil || payload.EnvironmentConfig.Version == nil {
		return r.sendBoundError(nil, "environment configuration is invalid")
	}
	version := payload.EnvironmentConfig.GetVersion()
	if version < 0 {
		return r.sendBoundError(nil, "environment configuration is invalid")
	}
	r.mu.RLock()
	previous := r.config
	previousProto := r.configProto
	ready := r.ready
	r.mu.RUnlock()
	if ready && version < previous.ConfigVersion {
		return nil
	}
	if ready && version == previous.ConfigVersion {
		if proto.Equal(previousProto, config.CanonicalConfiguration(payload.EnvironmentConfig.Config)) {
			return nil
		}
		return errors.New("the same environment config version must not change its content")
	}
	candidate, handled, err := r.applyEnvironmentConfig(ctx, ready, version, payload.EnvironmentConfig.Config)
	if err != nil || handled {
		return err
	}
	if candidate.EnvironmentID != "" && candidate.EnvironmentID != r.options.EnvironmentID {
		return errors.New("applied configuration changed environment scope")
	}
	candidate.EnvironmentID = r.options.EnvironmentID
	candidate.ConfigVersion = version
	r.mu.Lock()
	if err := r.status.ApplyConfiguration(version, candidate.ProxyURL); err != nil {
		r.mu.Unlock()
		return err
	}
	r.config = candidate
	r.configProto = config.CanonicalConfiguration(payload.EnvironmentConfig.Config)
	first := !r.ready
	r.ready = true
	r.mu.Unlock()
	r.setTemporaryResourcePolicyActive(true)
	r.gate.MarkReady()
	if first {
		return r.sendReady()
	}
	return r.sendStatus()
}

// applyEnvironmentConfig resolves the incoming snapshot through the configuration runtime when one
// is injected and through the injected applier otherwise. handled reports that the outcome was
// already answered on the control stream, so the caller must not publish a candidate.
func (r *Runtime) applyEnvironmentConfig(ctx context.Context, ready bool, version int64, wire *protocol.DaemonConfiguration) (config.DaemonConfig, bool, error) {
	if r.options.EnvironmentConfig != nil {
		result, err := r.options.EnvironmentConfig.Submit(ctx, version, wire)
		if err != nil {
			return config.DaemonConfig{}, false, err
		}
		switch result.Kind {
		case ConfigFailed:
			if err := r.sendStatus(); err != nil {
				return config.DaemonConfig{}, true, err
			}
			return config.DaemonConfig{}, true, nil
		case ConfigFatal:
			return config.DaemonConfig{}, false, fmt.Errorf("%w: environment configuration is inconsistent", ErrRuntimeFatal)
		default:
			return result.Config, false, nil
		}
	}
	candidate, err := r.options.ApplyConfig(ctx, version, wire)
	if err != nil {
		if !ready {
			_ = r.sendBoundError(nil, "environment configuration could not be applied")
			return config.DaemonConfig{}, true, nil
		}
		return config.DaemonConfig{}, true, r.sendBoundError(nil, "environment configuration could not be applied")
	}
	return candidate, false, nil
}
