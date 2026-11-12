package coding

import "testing"

// COD-F-004, COD-F-006
func TestDetectImageTypeUsesSupportedMagicBytes(t *testing.T) {
	for _, test := range []struct {
		name string
		data []byte
		want string
	}{
		{name: "png", data: []byte{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}, want: "image/png"},
		{name: "jpeg", data: []byte{0xff, 0xd8, 0xff}, want: "image/jpeg"},
		{name: "gif87a", data: []byte("GIF87a"), want: "image/gif"},
		{name: "gif89a", data: []byte("GIF89a"), want: "image/gif"},
		{name: "webp", data: []byte("RIFF0000WEBP"), want: "image/webp"},
		{name: "bad-riff", data: []byte("RIFF0000WAVE"), want: ""},
		{name: "short-webp", data: []byte("RIFF0000WEB"), want: ""},
		{name: "unknown", data: []byte("unknown"), want: ""},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := DetectImageType(test.data); got != test.want {
				t.Fatalf("image type=%q want=%q", got, test.want)
			}
		})
	}
}

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
