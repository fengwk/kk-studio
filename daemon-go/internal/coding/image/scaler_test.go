package image

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"image/png"
	"os"
	"runtime"
	"strings"
	"testing"
	"time"
)

// COD-N-007: exactly two scaling slots can be occupied before further work must wait.
func TestScalingConcurrencyLimitTwo(t *testing.T) {
	if cap(scalingSlots) != 2 {
		t.Fatalf("scaling slot capacity=%d want=2", cap(scalingSlots))
	}
	scalingSlots <- struct{}{}
	scalingSlots <- struct{}{}
	defer func() {
		<-scalingSlots
		<-scalingSlots
	}()
	if len(scalingSlots) != 2 {
		t.Fatalf("occupied scaling slots=%d want=2", len(scalingSlots))
	}
}

// COD-N-007: a third resize waits for an occupied slot and then completes when capacity opens.
func TestScaleContextWaitsForSlot(t *testing.T) {
	scalingSlots <- struct{}{}
	scalingSlots <- struct{}{}
	done := make(chan error, 1)
	data := tinyPNG(t)
	go func() {
		_, err := ScaleContext(context.Background(), "image/png", data, Original, 0)
		done <- err
	}()
	select {
	case err := <-done:
		t.Fatalf("resize completed while both slots were occupied: %v", err)
	case <-time.After(20 * time.Millisecond):
	}
	<-scalingSlots
	if err := <-done; err != nil {
		t.Fatalf("waiting resize failed after a slot opened: %v", err)
	}
	<-scalingSlots
}

// COD-N-003
func TestTierBoxesAndNoUpscale(t *testing.T) {
	cases := []struct {
		name         string
		tier         Tier
		w, h         int
		wantW, wantH int
	}{
		{"landscape720", P720, 2000, 1000, 1280, 640},
		{"portrait720", P720, 1000, 2000, 640, 1280},
		{"square720", P720, 2000, 2000, 720, 720},
		{"landscape1080", P1080, 4000, 2000, 1920, 960},
		{"portrait1080", P1080, 2000, 4000, 960, 1920},
		{"square1080", P1080, 4000, 4000, 1080, 1080},
		{"edgeNoUpscale", P720, 1280, 720, 1280, 720},
		{"smallNoUpscale", P1080, 300, 200, 300, 200},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var source bytes.Buffer
			if err := png.Encode(&source, patternImage(tc.w, tc.h, false)); err != nil {
				t.Fatal(err)
			}
			result, err := Scale("image/png", source.Bytes(), tc.tier, CatmullRom)
			if err != nil {
				t.Fatal(err)
			}
			cfg, _, err := image.DecodeConfig(bytes.NewReader(result.Bytes))
			if err != nil {
				t.Fatal(err)
			}
			if cfg.Width != tc.wantW || cfg.Height != tc.wantH {
				t.Fatalf("got %dx%d, want %dx%d", cfg.Width, cfg.Height, tc.wantW, tc.wantH)
			}
			if tc.w <= tc.wantW && tc.h <= tc.wantH && !bytes.Equal(source.Bytes(), result.Bytes) {
				t.Fatal("image within tier box was not passed through byte-for-byte")
			}
		})
	}
}

// COD-N-006
func TestScaleContextAppliesOutputLimitOnlyToResizedImage(t *testing.T) {
	var source bytes.Buffer
	if err := png.Encode(&source, patternImage(2000, 1000, false)); err != nil {
		t.Fatal(err)
	}
	wantResized, err := Scale("image/png", source.Bytes(), P720, ImagingLanczos)
	if err != nil {
		t.Fatal(err)
	}
	_, err = ScaleContext(context.Background(), "image/png", source.Bytes(), P720, 1)
	wantError := fmt.Sprintf("resized image exceeds 1 bytes (%d bytes)", len(wantResized.Bytes))
	if err == nil || err.Error() != wantError {
		t.Fatalf("oversized resized image error=%v want=%q", err, wantError)
	}
	original, err := ScaleContext(context.Background(), "image/png", source.Bytes(), Original, 1)
	if err != nil || original.Resized || !bytes.Equal(original.Bytes, source.Bytes()) {
		t.Fatalf("ORIGINAL must pass through without resize or byte limit: result=%#v err=%v", original, err)
	}
}

func TestBenchmarkResizeEnginesProduceRequestedDimensions(t *testing.T) {
	src := patternImage(63, 41, false)
	for _, engine := range []Engine{CatmullRomSingle, CatmullRom, ImagingLanczos} {
		got, err := ResizeForBenchmark(src, 19, 13, engine)
		if err != nil {
			t.Fatalf("engine %d: %v", engine, err)
		}
		if got.Bounds() != image.Rect(0, 0, 19, 13) {
			t.Fatalf("engine %d bounds=%v, want %v", engine, got.Bounds(), image.Rect(0, 0, 19, 13))
		}
	}
}

func TestEXIFOrientationsMapAllEightDirections(t *testing.T) {
	base := image.NewRGBA(image.Rect(0, 0, 3, 2))
	for y := 0; y < 2; y++ {
		for x := 0; x < 3; x++ {
			base.SetRGBA(x, y, color.RGBA{uint8(y*3 + x + 1), 0, 0, 255})
		}
	}
	want := map[int][]uint8{
		1: {1, 2, 3, 4, 5, 6},
		2: {3, 2, 1, 6, 5, 4},
		3: {6, 5, 4, 3, 2, 1},
		4: {4, 5, 6, 1, 2, 3},
		5: {1, 4, 2, 5, 3, 6},
		6: {4, 1, 5, 2, 6, 3},
		7: {6, 3, 5, 2, 4, 1},
		8: {3, 6, 2, 5, 1, 4},
	}
	for orientation, expected := range want {
		t.Run(string(rune('0'+orientation)), func(t *testing.T) {
			got := oriented{src: base, orientation: orientation}
			var values []uint8
			for y := 0; y < got.Bounds().Dy(); y++ {
				for x := 0; x < got.Bounds().Dx(); x++ {
					r, _, _, _ := got.At(x, y).RGBA()
					values = append(values, uint8(r>>8))
				}
			}
			if !bytes.Equal(values, expected) {
				t.Fatalf("orientation %d mapped to %v, want %v", orientation, values, expected)
			}
		})
	}
}

// COD-N-003, COD-N-005
func TestScaleReadsEXIFOrientationAndResizesDimensions(t *testing.T) {
	for orientation := 1; orientation <= 8; orientation++ {
		data := makeJPEGQuadrants(t, orientation)
		result, err := Scale("image/jpeg", data, P720, CatmullRom)
		if err != nil {
			t.Fatalf("orientation %d: %v", orientation, err)
		}
		cfg, _, err := image.DecodeConfig(bytes.NewReader(result.Bytes))
		if err != nil {
			t.Fatal(err)
		}
		if orientation >= 5 {
			if cfg.Width != 720 || cfg.Height != 1080 { // displayed source is 1000x1500, bounded to 720x1280
				t.Fatalf("orientation %d output %dx%d", orientation, cfg.Width, cfg.Height)
			}
		} else if cfg.Width != 1080 || cfg.Height != 720 {
			t.Fatalf("orientation %d output %dx%d", orientation, cfg.Width, cfg.Height)
		}
		pixels, err := jpeg.Decode(bytes.NewReader(result.Bytes))
		if err != nil {
			t.Fatal(err)
		}
		want := orientationQuadrants(orientation)
		for i, point := range [][2]int{{pixels.Bounds().Dx() / 4, pixels.Bounds().Dy() / 4}, {pixels.Bounds().Dx() * 3 / 4, pixels.Bounds().Dy() / 4}, {pixels.Bounds().Dx() / 4, pixels.Bounds().Dy() * 3 / 4}, {pixels.Bounds().Dx() * 3 / 4, pixels.Bounds().Dy() * 3 / 4}} {
			if got := dominantColor(pixels.At(point[0], point[1])); got != want[i] {
				t.Fatalf("orientation %d quadrant %d got %s, want %s", orientation, i, got, want[i])
			}
		}
	}
}

// COD-N-004
func TestAnimationMetadataMatchesJavaBehavior(t *testing.T) {
	gifData := animatedGIFHeader()
	if animated, err := gifAnimated(gifData); err != nil || !animated {
		t.Fatalf("GIF animated=%v err=%v", animated, err)
	}
	apngData, err := os.ReadFile("testdata/animated-default-frame.png")
	if err != nil {
		t.Fatal(err)
	}
	if present, err := apngPresent(apngData); err != nil || !present {
		t.Fatalf("APNG present=%v err=%v", present, err)
	}
	// Java ImageIO reports one image for this APNG and scales its default frame.
	apngResult, err := Scale("image/png", apngData, P720, CatmullRom)
	if err != nil {
		t.Fatalf("APNG default frame should follow Java's static PNG path: %v", err)
	}
	apngConfig, _, err := image.DecodeConfig(bytes.NewReader(apngResult.Bytes))
	if err != nil {
		t.Fatal(err)
	}
	if apngConfig.Width != 1280 || apngConfig.Height != 640 {
		t.Fatalf("APNG default frame scaled to %dx%d, want 1280x640", apngConfig.Width, apngConfig.Height)
	}
	apngPixels, err := png.Decode(bytes.NewReader(apngResult.Bytes))
	if err != nil {
		t.Fatal(err)
	}
	r, _, b, _ := apngPixels.At(20, 20).RGBA()
	if r <= b {
		t.Fatal("APNG result did not use its first/default frame")
	}
	webpData, err := os.ReadFile("testdata/animated-2000x1000.webp")
	if err != nil {
		t.Fatal(err)
	}
	if animated, err := webpAnimated(webpData); err != nil || !animated {
		t.Fatalf("WebP animated=%v err=%v", animated, err)
	}
	if _, err := Scale("image/webp", webpData, P720, CatmullRom); err == nil || !strings.Contains(err.Error(), "animated or multi-frame") {
		t.Fatalf("animated WebP was not rejected by animation metadata: %v", err)
	}
}

// COD-N-004
func TestDeclaredPixelBudgetRejectedBeforeDecodeAllocation(t *testing.T) {
	data := pngWithDeclaredDimensions(t, 10_000_001, 4)
	var before, after runtime.MemStats
	runtime.ReadMemStats(&before)
	_, err := Scale("image/png", data, Original, CatmullRom)
	runtime.ReadMemStats(&after)
	if err == nil || !strings.Contains(err.Error(), "pixel budget") {
		t.Fatalf("oversized declaration was not rejected by pixel budget: %v", err)
	}
	if after.TotalAlloc-before.TotalAlloc > 1<<20 {
		t.Fatalf("budget preflight allocated %d bytes", after.TotalAlloc-before.TotalAlloc)
	}
	nonPositive := pngWithDeclaredDimensions(t, 0, 4)
	if _, err := Scale("image/png", nonPositive, Original, CatmullRom); err == nil {
		t.Fatal("non-positive declared image dimensions were accepted")
	}
	exactBudget := pngWithDeclaredDimensions(t, 10_000, 4_000)
	if _, err := Scale("image/png", exactBudget, Original, CatmullRom); err != nil {
		t.Fatalf("exactly %d pixels must be allowed: %v", MaxPixels, err)
	}
}

// COD-N-006
func TestJPEGQualityFormatAndPNGAlpha(t *testing.T) {
	photo := patternImage(1600, 1000, false)
	var jpegBytes bytes.Buffer
	if err := jpeg.Encode(&jpegBytes, photo, &jpeg.Options{Quality: 88}); err != nil {
		t.Fatal(err)
	}
	result, err := Scale("image/jpeg", jpegBytes.Bytes(), P720, CatmullRom)
	if err != nil || result.MediaType != "image/jpeg" {
		t.Fatalf("JPEG result=%s err=%v", result.MediaType, err)
	}
	decoded, err := jpeg.Decode(bytes.NewReader(jpegBytes.Bytes()))
	if err != nil {
		t.Fatal(err)
	}
	resized, err := resize(oriented{src: decoded, orientation: 1}, 1152, 720, ImagingLanczos)
	if err != nil {
		t.Fatal(err)
	}
	outputConfig, format, err := image.DecodeConfig(bytes.NewReader(result.Bytes))
	if err != nil || format != "jpeg" || outputConfig.Width != 1152 || outputConfig.Height != 720 {
		t.Fatalf("opaque JPEG config=%#v format=%q err=%v", outputConfig, format, err)
	}
	var quality92, quality50 bytes.Buffer
	for quality, out := range map[int]*bytes.Buffer{92: &quality92, 50: &quality50} {
		if err := jpeg.Encode(out, toOpaque(resized), &jpeg.Options{Quality: quality}); err != nil {
			t.Fatal(err)
		}
	}
	if len(result.Bytes) != quality92.Len() || quality92.Len() <= quality50.Len() {
		t.Fatalf("quality-dependent JPEG sizes: result=%d q92=%d q50=%d", len(result.Bytes), quality92.Len(), quality50.Len())
	}
	alpha := patternImage(1600, 1000, true)
	var pngBytes bytes.Buffer
	if err := png.Encode(&pngBytes, alpha); err != nil {
		t.Fatal(err)
	}
	result, err = Scale("image/png", pngBytes.Bytes(), P720, CatmullRom)
	if err != nil || result.MediaType != "image/png" {
		t.Fatalf("alpha result=%s err=%v", result.MediaType, err)
	}
	decoded, err = png.Decode(bytes.NewReader(result.Bytes))
	if err != nil {
		t.Fatal(err)
	}
	_, _, _, a := decoded.At(0, 0).RGBA()
	if a == 0xffff {
		t.Fatal("transparent input alpha was lost")
	}
}

// COD-N-003, COD-N-004
func TestMultiFrameGIFCanPassThroughButCannotBeResized(t *testing.T) {
	data := animatedGIF(t)
	if _, err := Scale("image/gif", data, P720, CatmullRom); err == nil {
		t.Fatal("multi-frame GIF resize accepted")
	}
	if got, err := Scale("image/gif", data, Original, CatmullRom); err != nil || !bytes.Equal(got.Bytes, data) {
		t.Fatalf("original animation passthrough err=%v", err)
	}
}

// COD-N-004: a static GIF is decoded and resized to the PNG output format.
func TestStaticGIFResizesToPNG(t *testing.T) {
	frame := image.NewPaletted(image.Rect(0, 0, 1600, 1000), color.Palette{color.Black, color.White})
	frame.SetColorIndex(0, 0, 1)
	var source bytes.Buffer
	if err := gif.Encode(&source, frame, nil); err != nil {
		t.Fatal(err)
	}
	result, err := Scale("image/gif", source.Bytes(), P720, CatmullRom)
	if err != nil || result.MediaType != "image/png" {
		t.Fatalf("static GIF result=%#v err=%v", result, err)
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(result.Bytes))
	if err != nil || format != "png" || cfg.Width != 1152 || cfg.Height != 720 {
		t.Fatalf("resized GIF config=%#v format=%q err=%v", cfg, format, err)
	}
}

// COD-N-004: a static WebP is decoded, resized, and encoded as PNG.
func TestStaticWebPResizesToPNG(t *testing.T) {
	data, err := os.ReadFile("testdata/large-static.webp")
	if err != nil {
		t.Fatal(err)
	}
	result, err := Scale("image/webp", data, P720, CatmullRom)
	if err != nil || result.MediaType != "image/png" || !result.Resized {
		t.Fatalf("static WebP result=%#v err=%v", result, err)
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(result.Bytes))
	if err != nil || format != "png" || cfg.Width != 1280 || cfg.Height != 640 {
		t.Fatalf("resized WebP config=%#v format=%q err=%v", cfg, format, err)
	}
}

// COD-N-004: WebP metadata is recognized and an original-tier image passes through unchanged.
func TestWebPOriginalPassThrough(t *testing.T) {
	data, err := os.ReadFile("testdata/animated-2000x1000.webp")
	if err != nil {
		t.Fatal(err)
	}
	result, err := Scale("image/webp", data, Original, CatmullRom)
	if err != nil || result.MediaType != "image/webp" || result.OriginalWidth != 2000 || result.OriginalHeight != 1000 || !bytes.Equal(result.Bytes, data) {
		t.Fatalf("WebP original result=%#v err=%v", result, err)
	}
}

func tinyPNG(t *testing.T) []byte {
	t.Helper()
	var out bytes.Buffer
	if err := png.Encode(&out, image.NewRGBA(image.Rect(0, 0, 2, 2))); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}

func pngWithDeclaredDimensions(t *testing.T, width, height uint32) []byte {
	t.Helper()
	data := tinyPNG(t)
	binary.BigEndian.PutUint32(data[16:20], width)
	binary.BigEndian.PutUint32(data[20:24], height)
	binary.BigEndian.PutUint32(data[29:33], crc32.ChecksumIEEE(data[12:29]))
	return data
}

func makeJPEGQuadrants(t *testing.T, orientation int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 1500, 1000))
	colors := []color.RGBA{{255, 0, 0, 255}, {0, 255, 0, 255}, {0, 0, 255, 255}, {255, 255, 0, 255}}
	for y := 0; y < 1000; y++ {
		for x := 0; x < 1500; x++ {
			index := 0
			if y >= 500 {
				index += 2
			}
			if x >= 750 {
				index++
			}
			img.SetRGBA(x, y, colors[index])
		}
	}
	var encoded bytes.Buffer
	if err := jpeg.Encode(&encoded, img, &jpeg.Options{Quality: 100}); err != nil {
		t.Fatal(err)
	}
	return injectOrientation(encoded.Bytes(), orientation)
}

func injectOrientation(jpegData []byte, orientation int) []byte {
	tiff := make([]byte, 26)
	copy(tiff, []byte{'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0})
	binary.LittleEndian.PutUint16(tiff[18:20], uint16(orientation))
	exif := append([]byte("Exif\x00\x00"), tiff...)
	segment := make([]byte, 4+len(exif))
	segment[0], segment[1] = 0xff, 0xe1
	binary.BigEndian.PutUint16(segment[2:4], uint16(len(exif)+2))
	copy(segment[4:], exif)
	return append(append(append([]byte{}, jpegData[:2]...), segment...), jpegData[2:]...)
}

func orientationQuadrants(orientation int) []string {
	return map[int][]string{
		1: {"red", "green", "blue", "yellow"},
		2: {"green", "red", "yellow", "blue"},
		3: {"yellow", "blue", "green", "red"},
		4: {"blue", "yellow", "red", "green"},
		5: {"red", "blue", "green", "yellow"},
		6: {"blue", "red", "yellow", "green"},
		7: {"yellow", "green", "blue", "red"},
		8: {"green", "yellow", "red", "blue"},
	}[orientation]
}

func dominantColor(c color.Color) string {
	r, g, b, _ := c.RGBA()
	switch {
	case r > 0xc000 && g < 0x5000 && b < 0x5000:
		return "red"
	case g > 0xc000 && r < 0x5000 && b < 0x5000:
		return "green"
	case b > 0xc000 && r < 0x5000 && g < 0x5000:
		return "blue"
	case r > 0xc000 && g > 0xc000 && b < 0x5000:
		return "yellow"
	default:
		return "other"
	}
}

func patternImage(w, h int, alpha bool) *image.NRGBA {
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			v := uint8((x*13 + y*7 + (x*y)%251) & 255)
			a := uint8(255)
			if alpha && (x+y)%3 == 0 {
				a = 128
			}
			img.SetNRGBA(x, y, color.NRGBA{v, uint8(v*3 + 17), uint8(v*7 + 9), a})
		}
	}
	return img
}

func animatedGIFHeader() []byte {
	return []byte{'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 1, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 1, 0, 0, 0x3b}
}

func animatedGIF(t *testing.T) []byte {
	t.Helper()
	frame := image.NewPaletted(image.Rect(0, 0, 1600, 1000), color.Palette{color.Black, color.White})
	anim := &gif.GIF{Image: []*image.Paletted{frame, frame}, Delay: []int{1, 1}}
	var out bytes.Buffer
	if err := gif.EncodeAll(&out, anim); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}
