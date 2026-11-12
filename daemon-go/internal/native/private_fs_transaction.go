package native

import (
	"io"
)

type privateFileTransactionTemp interface {
	io.Writer
	Sync() error
	Close() error
	Name() string
}

func publishPrivateFileTransaction(tmp privateFileTransactionTemp, path string, data []byte, rename func(string, string) error, remove func(string) error, verify func(string) error) error {
	tmpPath := tmp.Name()
	published := false
	defer func() {
		_ = tmp.Close()
		if !published {
			_ = remove(tmpPath)
		}
	}()
	if err := writeAll(tmp, data); err != nil {
		return err
	}
	if err := tmp.Sync(); err != nil {
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := rename(tmpPath, path); err != nil {
		return err
	}
	published = true
	return verify(path)
}
