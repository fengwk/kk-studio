package coding

import "bytes"

// DetectMediaType identifies supported non-image media exclusively from its header.
func DetectMediaType(data []byte) string {
	if len(data) < 4 {
		return ""
	}
	if bytes.HasPrefix(data, []byte("%PDF")) {
		return "application/pdf"
	}
	if len(data) >= 12 && bytes.Equal(data[:4], []byte("RIFF")) && bytes.Equal(data[8:12], []byte("WAVE")) {
		return "audio/wav"
	}
	if bytes.HasPrefix(data, []byte("ID3")) {
		return "audio/mpeg"
	}
	if len(data) >= 12 && bytes.Equal(data[4:8], []byte("ftyp")) {
		brand := bytes.ToLower(data[8:12])
		if bytes.HasPrefix(brand, []byte("m4a")) || bytes.HasPrefix(brand, []byte("mp4a")) {
			return ""
		}
		if bytes.HasPrefix(brand, []byte("qt")) {
			return "video/quicktime"
		}
		return "video/mp4"
	}
	if data[0] == 0xff && (data[1] == 0xfb || data[1] == 0xf3 || data[1] == 0xf2) {
		return "audio/mpeg"
	}
	return ""
}

// DetectImageType returns the MIME type of recognized image signatures.
func DetectImageType(data []byte) string {
	switch {
	case bytes.HasPrefix(data, []byte{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}):
		return "image/png"
	case bytes.HasPrefix(data, []byte{0xff, 0xd8, 0xff}):
		return "image/jpeg"
	case bytes.HasPrefix(data, []byte("GIF87a")), bytes.HasPrefix(data, []byte("GIF89a")):
		return "image/gif"
	case len(data) >= 12 && bytes.Equal(data[:4], []byte("RIFF")) && bytes.Equal(data[8:12], []byte("WEBP")):
		return "image/webp"
	default:
		return ""
	}
}
