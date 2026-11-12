package native

import (
	"context"
	"errors"
)

// EnsurePrivateDirectory creates or tightens an owner-only directory and verifies its ACL.
func EnsurePrivateDirectory(path string) error { return ensurePrivateDirectory(path) }

// WritePrivateFile atomically writes a file using the platform's owner-only file protections.
func WritePrivateFile(path string, data []byte) error { return writePrivateFile(path, data) }

// VerifyPrivateFile confirms a file is a regular file protected from other users.
func VerifyPrivateFile(path string) error { return verifyPrivateFile(path) }

// PrivateConfigCacheWriter adapts the native private-file capability to runtime cache writes.
type PrivateConfigCacheWriter struct{}

func (PrivateConfigCacheWriter) WritePrivateAtomic(ctx context.Context, path string, data []byte) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if path == "" {
		return errors.New("private cache path is required")
	}
	return writePrivateFile(path, data)
}
