package coding

import (
	"fmt"
	"strings"
	"testing"
)

// BenchmarkEditReplaceAll measures the linear splice on a file where every line is a hit, which is
// the case the replaced quadratic line-ending reconstruction handled worst.
func BenchmarkEditReplaceAll(b *testing.B) {
	for _, lines := range []int{20000, 80000} {
		content := strings.Repeat("value = 1\n", lines)
		b.Run(fmt.Sprintf("lines-%d", lines), func(b *testing.B) {
			source := newEditSource(content)
			oldText, newText := "value = 1", "value = 2"
			entries := []editEntry{{OldString: &oldText, NewString: &newText, ReplaceAll: true}}
			b.ReportAllocs()
			for range b.N {
				replacements, err := planEdits("/srv/file.txt", source, entries)
				if err != nil {
					b.Fatal(err)
				}
				if updated := applyEdits(content, source, replacements); len(updated) != len(content) {
					b.Fatalf("unexpected length %d", len(updated))
				}
			}
		})
	}
}
