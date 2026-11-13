//go:build linux || darwin

package native

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"syscall"
)

func secureNewPrivateDirectory(path string) error { return ensurePrivateDirectory(path) }

type posixPrivateFSAPI struct {
	lstat    func(string) (os.FileInfo, error)
	mkdirAll func(string, os.FileMode) error
	chmod    func(string, os.FileMode) error
}

func ensurePrivateDirectory(path string) error {
	return ensurePrivateDirectoryWithAPI(path, posixPrivateFSAPI{lstat: os.Lstat, mkdirAll: os.MkdirAll, chmod: os.Chmod})
}

func ensurePrivateDirectoryWithAPI(path string, api posixPrivateFSAPI) error {
	st, err := api.lstat(path)
	if err == nil {
		if !st.IsDir() || st.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("the private directory path is not a real directory: %s", path)
		}
		if !ownedByCurrentUser(st) {
			return fmt.Errorf("the private directory is not owned by the current user: %s", path)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if errors.Is(err, os.ErrNotExist) {
		if err := api.mkdirAll(path, 0700); err != nil {
			return err
		}
	}
	if err := api.chmod(path, 0700); err != nil {
		return err
	}
	return verifyPrivateDirectory(path)
}

func verifyPrivateDirectory(path string) error {
	st, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !st.IsDir() || st.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("the private directory path is not a real directory: %s", path)
	}
	if !ownedByCurrentUser(st) {
		return fmt.Errorf("the private directory is not owned by the current user: %s", path)
	}
	if st.Mode().Perm() != 0700 {
		return fmt.Errorf("the private directory permissions are not owner-only: %s", path)
	}
	return nil
}

func writePrivateFile(path string, data []byte) error {
	if err := verifyPrivateDirectory(filepath.Dir(path)); err != nil {
		return err
	}
	if st, err := os.Lstat(path); err == nil {
		if !st.Mode().IsRegular() || st.Mode()&os.ModeSymlink != 0 || !ownedByCurrentUser(st) {
			return fmt.Errorf("the private file path is not a real owned file: %s", path)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	tmp, err := os.OpenFile(filepath.Join(filepath.Dir(path), "."+filepath.Base(path)+"."+randomSuffix()+".tmp"), os.O_WRONLY|os.O_CREATE|os.O_EXCL|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return err
	}
	return publishPrivateFileTransaction(tmp, path, data, os.Rename, os.Remove, verifyPrivateFile)
}

func createPrivateFile(path string) error {
	if err := verifyPrivateDirectory(filepath.Dir(path)); err != nil {
		return err
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return err
	}
	return file.Close()
}

func verifyPrivateFile(path string) error {
	return verifyPrivateFileWithLstat(path, os.Lstat)
}

func verifyPrivateFileWithLstat(path string, lstat func(string) (os.FileInfo, error)) error {
	st, err := lstat(path)
	if err != nil {
		return err
	}
	if !st.Mode().IsRegular() || st.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("the private file path is not a real file: %s", path)
	}
	if !ownedByCurrentUser(st) {
		return fmt.Errorf("the private file is not owned by the current user: %s", path)
	}
	if st.Mode().Perm() != 0600 {
		return fmt.Errorf("the private file permissions are not owner-only: %s", path)
	}
	return nil
}

func ownedByCurrentUser(info os.FileInfo) bool {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return false
	}
	return uint32(stat.Uid) == uint32(os.Getuid())
}
