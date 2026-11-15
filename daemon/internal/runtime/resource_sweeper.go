package runtime

import (
	"context"
	"fmt"
	"reflect"
	"time"
)

func (r *Runtime) setTemporaryResourcePolicy(ttl, interval time.Duration, active bool) {
	r.resourcePolicyMu.Lock()
	r.resourceTTL = ttl
	r.resourceSweepInterval = interval
	r.resourcePolicyActive = active
	r.resourcePolicyMu.Unlock()
	select {
	case r.resourcePolicyChanged <- struct{}{}:
	default:
	}
}

func (r *Runtime) setTemporaryResourcePolicyActive(active bool) {
	r.resourcePolicyMu.Lock()
	r.resourcePolicyActive = active
	r.resourcePolicyMu.Unlock()
	select {
	case r.resourcePolicyChanged <- struct{}{}:
	default:
	}
}

func (r *Runtime) temporaryResourcePolicy() (time.Duration, time.Duration, bool) {
	r.resourcePolicyMu.Lock()
	defer r.resourcePolicyMu.Unlock()
	return r.resourceTTL, r.resourceSweepInterval, r.resourcePolicyActive
}

func safeSweepErrorType(err error) string {
	typeOfError := reflect.TypeOf(err)
	for typeOfError.Kind() == reflect.Pointer {
		typeOfError = typeOfError.Elem()
	}
	if typeOfError.PkgPath() == "errors" && typeOfError.Name() == "errorString" {
		return "Error"
	}
	return typeOfError.Name()
}

func (r *Runtime) temporaryResourceSweepLoop(ctx context.Context, done chan<- struct{}) {
	defer close(done)
	for {
		ttl, interval, active := r.temporaryResourcePolicy()
		if r.options.TemporaryResources == nil || !active || ttl <= 0 || interval <= 0 {
			select {
			case <-ctx.Done():
				return
			case <-r.resourcePolicyChanged:
				continue
			}
		}
		timer := time.NewTimer(interval)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-r.resourcePolicyChanged:
			timer.Stop()
			continue
		case <-timer.C:
		}
		if _, err := r.options.TemporaryResources.Sweep(ttl); err != nil && r.options.ReportError != nil {
			r.options.ReportError(fmt.Errorf("daemon temporary resource sweep failed: %s", safeSweepErrorType(err)))
		}
	}
}
