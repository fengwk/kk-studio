package native

import "fmt"

func newUnsupportedSupervisor(platform string) (Supervisor, error) {
	return nil, fmt.Errorf("the native process supervisor does not support %s", platform)
}

func unsupportedDirectoryLockError(platform string) error {
	return fmt.Errorf("the directory lock is not supported on %s", platform)
}
