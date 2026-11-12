package search

import (
	"os"
	"testing"
)

// BenchmarkProfileGrep is an opt-in profiling target; set SEARCH_PROFILE_ROOT to a read-only dataset.
func BenchmarkProfileGrep(b *testing.B) {
	root := os.Getenv("SEARCH_PROFILE_ROOT")
	if root == "" {
		b.Fatal("SEARCH_PROFILE_ROOT is required")
	}
	b.ResetTimer()
	mode := os.Getenv("SEARCH_PROFILE_MODE")
	pattern, literal, ignoreCase := "func main", true, false
	if mode == "regex" {
		pattern, literal = `func \w+\(ctx context\.Context`, false
	}
	if mode == "ignore-case" {
		ignoreCase = true
	}
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		if _, err := Grep(root, pattern, literal, ignoreCase, "", 0); err != nil {
			b.Fatal(err)
		}
	}
}
