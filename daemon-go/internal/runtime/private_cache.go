package runtime

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
)

// FilePrivateCacheWriter atomically replaces a private cache file. Replace with the native package writer at final assembly.
type FilePrivateCacheWriter struct{}

func (FilePrivateCacheWriter) WritePrivateAtomic(ctx context.Context, path string, data []byte) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if path == "" || !filepath.IsAbs(path) {
		return errors.New("private cache path must be absolute")
	}
	if info, err := os.Lstat(path); err == nil {
		if !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("private cache path is not a regular file")
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return errors.New("private cache path cannot be inspected")
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".daemon-config-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	published := false
	defer func() {
		if !published {
			_ = os.Remove(temporaryPath)
		}
	}()
	if err := temporary.Chmod(0o600); err != nil {
		_ = temporary.Close()
		return err
	}
	written, err := temporary.Write(data)
	if err == nil && written != len(data) {
		err = io.ErrShortWrite
	}
	if err == nil {
		err = temporary.Sync()
	}
	closeErr := temporary.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		return err
	}
	published = true
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Mode().Perm() != 0o600 {
		return errors.New("private cache file failed readback validation")
	}
	actual, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(actual, data) {
		return errors.New("private cache file failed readback validation")
	}
	return nil
}
