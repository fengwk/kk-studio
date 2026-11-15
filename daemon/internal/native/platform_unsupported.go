//go:build !linux && !darwin && !windows

package native

import "errors"

func ensurePrivateDirectory(string) error {
	return errors.New("private directories are unsupported on this platform")
}
func verifyPrivateDirectory(string) error {
	return errors.New("private directories are unsupported on this platform")
}
func verifyPrivateFile(string) error {
	return errors.New("private files are unsupported on this platform")
}
func createPrivateFile(string) error {
	return errors.New("private files are unsupported on this platform")
}
func writePrivateFile(string, []byte) error {
	return errors.New("private files are unsupported on this platform")
}
func secureNewPrivateDirectory(string) error {
	return errors.New("private directories are unsupported on this platform")
}

func openPlatformLockHandle(string) (directoryLockHandle, error) {
	return nil, errors.New("directory locks are unsupported on this platform")
}
