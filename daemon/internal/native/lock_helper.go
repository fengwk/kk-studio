package native

import (
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const lockHelperUsage = "usage: lock-helper hold <directory> | lock-helper wait <directory> <timeout-seconds>\n"

var lockTimeoutPattern = regexp.MustCompile(`^[0-9]{1,4}$`)

// LockHelperMain executes the install-lock internal subcommand and returns its process exit code.
func LockHelperMain(args []string) int { return runLockHelper(args, os.Stdin, os.Stdout, os.Stderr) }

type lockHelperAPI struct {
	acquire func(string, string) (DirectoryLock, error)
	wait    func(context.Context, string, string, time.Duration) error
}

func runLockHelper(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	return runLockHelperWithAPI(args, stdin, stdout, stderr, lockHelperAPI{
		acquire: AcquireDirectoryLock,
		wait:    WaitForDirectoryLock,
	})
}

func runLockHelperWithAPI(args []string, stdin io.Reader, stdout, stderr io.Writer, api lockHelperAPI) int {
	usage := func() int { _, _ = io.WriteString(stderr, lockHelperUsage); return 2 }
	if len(args) < 2 || !filepath.IsAbs(args[1]) || hasControl(args[1]) {
		return usage()
	}
	switch args[0] {
	case "hold":
		if len(args) != 2 {
			return usage()
		}
		lock, err := api.acquire(args[1], InstallLockFileName)
		if err != nil {
			if isLockBusy(err) {
				_, _ = io.WriteString(stderr, "install operation lock is busy\n")
				return 75
			}
			_, _ = io.WriteString(stderr, "cannot acquire the install operation lock\n")
			return 77
		}
		if _, err := io.WriteString(stdout, "locked\n"); err != nil {
			_ = lock.Close()
			return 77
		}
		_, _ = io.Copy(io.Discard, stdin)
		if err := lock.Close(); err != nil {
			_, _ = io.WriteString(stderr, "cannot release the install operation lock\n")
			return 77
		}
		return 0
	case "wait":
		if len(args) != 3 || !lockTimeoutPattern.MatchString(args[2]) {
			return usage()
		}
		seconds, err := strconv.Atoi(args[2])
		if err != nil || seconds > 3600 {
			return usage()
		}
		ctx, cancel := context.WithTimeout(context.Background(), time.Duration(seconds)*time.Second)
		defer cancel()
		err = api.wait(ctx, args[1], DaemonLockFileName, time.Duration(seconds)*time.Second)
		if err == nil {
			return 0
		}
		if err == ErrDirectoryLockTimeout || err == context.DeadlineExceeded {
			_, _ = io.WriteString(stderr, "daemon runtime lock wait timed out\n")
			return 76
		}
		_, _ = io.WriteString(stderr, "cannot inspect the daemon runtime lock\n")
		return 77
	default:
		return usage()
	}
}

func hasControl(value string) bool {
	return strings.IndexFunc(value, func(r rune) bool { return r < 0x20 || r == 0x7f }) >= 0
}
func isLockBusy(err error) bool { return errors.Is(err, ErrDirectoryLockBusy) }

// HelperMain is the executable's process-scope helper entry point.
func HelperMain(args []string) int { return helperMain(args) }
