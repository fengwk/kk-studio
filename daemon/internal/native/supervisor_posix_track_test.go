//go:build linux || darwin

package native

import (
	"context"
	"errors"
	"reflect"
	"testing"
)

func TestPOSIXTrackDescendantWithSnapshot(t *testing.T) {
	for _, test := range []struct {
		name        string
		members     []sessionMember
		snapshotErr error
		wantErr     error
		wantTrack   map[int]trackedSession
	}{
		{
			name:        "snapshot error",
			snapshotErr: errors.New("snapshot failed"),
			wantErr:     errors.New("snapshot failed"),
		},
		{
			name:    "foreign process",
			members: []sessionMember{{PID: 11, Parent: 999, Session: 30, Identity: "child"}},
			wantErr: errors.New("the process is not a verified descendant of the managed process"),
		},
		{
			name:    "unknown session identity",
			members: []sessionMember{{PID: 10, Parent: 1, Session: 20, Identity: "root"}, {PID: 11, Parent: 10, Session: 0}},
			wantErr: errors.New("the process session identity is unknown"),
		},
		{
			name:    "same managed session",
			members: []sessionMember{{PID: 10, Parent: 1, Session: 20, Identity: "root"}, {PID: 11, Parent: 10, Session: 20, Identity: "child"}},
		},
		{
			name:    "escaped session is tracked with identity",
			members: []sessionMember{{PID: 10, Parent: 1, Session: 20, Identity: "root"}, {PID: 11, Parent: 10, Session: 30, Identity: "child-start"}},
			wantTrack: map[int]trackedSession{
				30: {ChildPID: 11, Session: 30, Identity: "child-start"},
			},
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			root := &posixProcess{pid: 10, sid: 20, trackedSessions: make(map[int]trackedSession)}
			supervisor := &PosixSupervisor{processes: map[int]*posixProcess{10: root}}
			snapshot := func() ([]sessionMember, error) { return test.members, test.snapshotErr }
			err := supervisor.trackDescendantWithMembers(context.Background(), 10, 11, snapshot)
			if test.wantErr != nil {
				if err == nil || err.Error() != test.wantErr.Error() {
					t.Fatalf("tracking error = %v, want %v", err, test.wantErr)
				}
			} else if err != nil {
				t.Fatalf("tracking error = %v", err)
			}
			wantTrack := test.wantTrack
			if wantTrack == nil {
				wantTrack = map[int]trackedSession{}
			}
			if !reflect.DeepEqual(root.trackedSessions, wantTrack) {
				t.Fatalf("tracked sessions = %+v, want %+v", root.trackedSessions, wantTrack)
			}
		})
	}
}
