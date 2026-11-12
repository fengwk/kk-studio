package coding

import "testing"

// COD-F-005
func TestDetectMediaTypeHeaderRules(t *testing.T) {
	for _, test := range []struct {
		name string
		head string
		want string
	}{
		{name: "short", head: "pdf", want: ""},
		{name: "pdf", head: "%PDF", want: "application/pdf"},
		{name: "wav", head: "RIFF0000WAVE", want: "audio/wav"},
		{name: "id3", head: "ID3-tag", want: "audio/mpeg"},
		{name: "m4a", head: "0000ftypM4A ", want: ""},
		{name: "mp4a", head: "0000ftypmp4a", want: ""},
		{name: "quicktime", head: "0000ftypqt  ", want: "video/quicktime"},
		{name: "mp4", head: "0000ftypisom", want: "video/mp4"},
		{name: "mp3-frame-fb-too-short", head: string([]byte{0xff, 0xfb, 0}), want: ""},
		{name: "mp3-frame-fb", head: string([]byte{0xff, 0xfb, 0, 0}), want: "audio/mpeg"},
		{name: "mp3-frame-fb-upper", head: string([]byte{0xff, 0xfb, 0xaa, 0xbb}), want: "audio/mpeg"},
		{name: "mp3-frame-f3", head: string([]byte{0xff, 0xf3, 0, 0}), want: "audio/mpeg"},
		{name: "mp3-frame-f2", head: string([]byte{0xff, 0xf2, 0, 0}), want: "audio/mpeg"},
		{name: "unsupported", head: "text", want: ""},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := DetectMediaType([]byte(test.head)); got != test.want {
				t.Fatalf("type=%q want=%q", got, test.want)
			}
		})
	}
}
