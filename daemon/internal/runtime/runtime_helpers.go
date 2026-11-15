package runtime

func stringPtr(s string) *string { return &s }

func sameOptionalString(left, right *string) bool {
	if left == nil || right == nil {
		return left == nil && right == nil
	}
	return *left == *right
}

func boolPtr(b bool) *bool { return &b }

func int64Ptr(v int64) *int64 { return &v }
