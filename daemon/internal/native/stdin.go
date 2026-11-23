package native

import (
	"fmt"
	"os"
)

// openNullStdin opens a real null device file that a process can both inherit as
// standard input and receive discarded writes on.
func openNullStdin() (*os.File, error) {
	file, err := os.OpenFile(os.DevNull, os.O_RDWR, 0)
	if err != nil {
		return nil, fmt.Errorf("cannot open the null stdin device: %w", err)
	}
	return file, nil
}
