package native

import (
	"os"
	"strconv"
	"testing"
)

// NAT-T-002 verifies state publication leaves only the atomically published PID file.
func TestFixture_AtomicPIDPublish(t *testing.T) {
	for _, test := range []struct {
		name string
		pid  int
	}{
		{name: "default", pid: os.Getpid()},
		{name: "explicit", pid: os.Getpid() + 1},
	} {
		t.Run(test.name, func(t *testing.T) {
			directory := stateTestDir(t)
			if err := publishText(directory, "helper.pid", strconv.Itoa(test.pid)); err != nil {
				t.Fatal(err)
			}
			contents, err := readState(directory, "helper.pid")
			if err != nil {
				t.Fatal(err)
			}
			if string(contents) != strconv.Itoa(test.pid) {
				t.Fatalf("published PID = %q, want %d", contents, test.pid)
			}
			entries, err := os.ReadDir(directory)
			if err != nil {
				t.Fatal(err)
			}
			if len(entries) != 1 || entries[0].Name() != "helper.pid" {
				t.Fatalf("files after publication = %v, want only helper.pid", entries)
			}
		})
	}
}
