package image

import (
	"context"
	"encoding/binary"
	stdimage "image"
	"strings"
	"testing"
)

// COD-F-004
func TestImageMetadataRejectsMalformedContainers(t *testing.T) {
	gifHeader := []byte("GIF89a\x01\x00\x01\x00\x00\x00\x00")
	gifColorTable := append([]byte(nil), gifHeader...)
	gifColorTable[10] = 0x80
	gifExtension := append(append([]byte(nil), gifHeader...), 0x21)
	gifDescriptor := append(append([]byte(nil), gifHeader...), 0x2c, 0, 0, 0)
	gifMarker := append(append([]byte(nil), gifHeader...), 0x7f)
	gifImageData := append(append([]byte(nil), gifHeader...), 0x2c)
	gifImageData = append(gifImageData, make([]byte, 9)...)
	gifImageData = append(gifImageData, 2, 3, 1)
	gifSubBlock := append(append([]byte(nil), gifHeader...), 0x2c)
	gifSubBlock = append(gifSubBlock, make([]byte, 9)...)
	gifSubBlock = append(gifSubBlock, 2)

	for _, test := range []struct {
		name string
		data []byte
		want string
	}{
		{"short-gif", []byte("GIF"), "malformed GIF header"},
		{"bad-gif-signature", append([]byte("BAD"), gifHeader[3:]...), "malformed GIF header"},
		{"gif-color-table", gifColorTable, "truncated GIF color table"},
		{"gif-extension", gifExtension, "truncated GIF extension"},
		{"gif-descriptor", gifDescriptor, "truncated GIF image descriptor"},
		{"gif-marker", gifMarker, "invalid GIF block marker"},
		{"gif-trailer", gifHeader, "GIF trailer is missing"},
		{"gif-image-data", gifImageData, "truncated GIF sub-block data"},
		{"gif-sub-block", gifSubBlock, "truncated GIF sub-block"},
	} {
		t.Run(test.name, func(t *testing.T) {
			_, err := gifAnimated(test.data)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("error=%v want fragment %q", err, test.want)
			}
		})
	}
}

// COD-F-004
func TestAnimationMetadataValidatesPNGAndWebPChunks(t *testing.T) {
	pngHeader := []byte("\x89PNG\r\n\x1a\n")
	pngTruncated := append(append([]byte(nil), pngHeader...), 0, 0, 0, 4, 'I', 'D', 'A', 'T', 0, 0, 0, 0)
	pngControl := make([]byte, 16)
	binary.BigEndian.PutUint32(pngControl[:4], 4)
	copy(pngControl[4:8], "acTL")
	pngInvalidControl := append(append([]byte(nil), pngHeader...), pngControl...)
	pngIDAT := make([]byte, 12)
	copy(pngIDAT[4:8], "IDAT")
	pngBeforeControl := append(append([]byte(nil), pngHeader...), pngIDAT...)

	for _, test := range []struct {
		name string
		fn   func([]byte) (bool, error)
		data []byte
		want string
	}{
		{"png-header", apngPresent, []byte("PNG"), "truncated PNG header"},
		{"png-chunk", apngPresent, pngTruncated, "truncated PNG chunk"},
		{"png-control", apngPresent, pngInvalidControl, "invalid APNG animation control chunk"},
		{"webp-header", webpAnimated, []byte("RIFFbad!NOPE"), "malformed WebP RIFF header"},
		{"webp-chunk", webpAnimated, []byte("RIFF\x0c\x00\x00\x00WEBPVP8X\x08\x00\x00\x00"), "truncated WebP chunk"},
		{"webp-extended-header", webpAnimated, []byte("RIFF\x15\x00\x00\x00WEBPVP8X\x02\x00\x00\x00\x00\x00"), "invalid WebP extended header"},
	} {
		t.Run(test.name, func(t *testing.T) {
			_, err := test.fn(test.data)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("error=%v want fragment %q", err, test.want)
			}
		})
	}
	staticWebP := []byte("RIFF\x16\x00\x00\x00WEBPVP8X\x0a\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00")
	if animated, err := webpAnimated(staticWebP); err != nil || animated {
		t.Fatalf("static WebP animated=%v err=%v", animated, err)
	}
	if animated, err := apngPresent(pngBeforeControl); err != nil || animated {
		t.Fatalf("PNG IDAT before control animated=%v err=%v", animated, err)
	}
}

// COD-F-003
func TestParseOrientationSupportsBigEndianAndRejectsMalformedTIFF(t *testing.T) {
	bigEndian := []byte{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 1, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0}
	if orientation, err := parseOrientation(bigEndian); err != nil || orientation != 6 {
		t.Fatalf("orientation=%d err=%v", orientation, err)
	}
	invalid := []struct {
		name string
		data []byte
	}{
		{"short", []byte("II")},
		{"byte-order", []byte("XX\x00*\x00\x00\x00\x08")},
		{"magic", []byte("II\x00+\x00\x00\x00\x08")},
		{"offset", []byte("II\x00*\x00\x00\x00\xff")},
		{"entries", []byte("II\x00*\x00\x00\x00\x08\x00\x01")},
	}
	for _, test := range invalid {
		t.Run(test.name, func(t *testing.T) {
			if _, err := parseOrientation(test.data); err == nil {
				t.Fatal("expected malformed TIFF error")
			}
		})
	}
	otherTag := []byte("II\x2a\x00\x08\x00\x00\x00\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00")
	if orientation, err := parseOrientation(otherTag); err != nil || orientation != 1 {
		t.Fatalf("non-orientation tag orientation=%d err=%v", orientation, err)
	}
}

// COD-F-009
func TestOrientedImageExposesSourceColorModel(t *testing.T) {
	orientedImage := oriented{src: stdimage.NewRGBA(stdimage.Rect(0, 0, 1, 1)), orientation: 1}
	if orientedImage.ColorModel() == nil {
		t.Fatal("oriented image returned a nil color model")
	}
}

// COD-F-004
func TestScaleRejectsEmptyAndUnparseableImages(t *testing.T) {
	if _, err := Scale("image/png", nil, P720, ImagingLanczos); err == nil || err.Error() != "empty image" {
		t.Fatalf("empty image error=%v", err)
	}
	if _, err := Scale("image/png", []byte("not an image"), P720, ImagingLanczos); err == nil || !strings.Contains(err.Error(), "cannot parse image") {
		t.Fatalf("unparseable image error=%v", err)
	}
}

// COD-F-004
func TestScaleRejectsUnknownTierAndMismatchedMedia(t *testing.T) {
	data := tinyPNG(t)
	if _, err := Scale("image/png", data, Tier(99), ImagingLanczos); err == nil || !strings.Contains(err.Error(), "unknown tier") {
		t.Fatalf("unknown tier error=%v", err)
	}
	if _, err := Scale("image/webp", data, P720, ImagingLanczos); err == nil || !strings.Contains(err.Error(), "does not match format") {
		t.Fatalf("media mismatch error=%v", err)
	}
}

// COD-N-006, COD-N-007
func TestScaleContextReturnsWaitingCancellation(t *testing.T) {
	scalingSlots <- struct{}{}
	scalingSlots <- struct{}{}
	defer func() {
		<-scalingSlots
		<-scalingSlots
	}()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := ScaleContext(ctx, "image/png", nil, P720, 0); err != context.Canceled {
		t.Fatalf("cancellation error=%v", err)
	}
}

// COD-F-003
func TestJPEGOrientationRejectsMalformedSegments(t *testing.T) {
	for _, test := range []struct {
		name string
		data []byte
		want string
	}{
		{"truncated", []byte{0xff, 0xd8, 0xff, 0xff, 0xe1, 0}, "truncated JPEG segment"},
		{"invalid-length", []byte{0xff, 0xd8, 0xff, 0xe1, 0, 1}, "invalid JPEG segment length"},
	} {
		t.Run(test.name, func(t *testing.T) {
			_, err := jpegOrientation("image/jpeg", test.data)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("error=%v want fragment %q", err, test.want)
			}
		})
	}
}
