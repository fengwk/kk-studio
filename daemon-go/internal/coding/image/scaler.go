// Package image scales image inputs to bounded display tiers and reports encoded media.
package image

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"image"
	"image/color"
	_ "image/gif"
	"image/jpeg"
	"image/png"
	"math"

	"github.com/disintegration/imaging"
	_ "golang.org/x/image/bmp"
	"golang.org/x/image/draw"
	_ "golang.org/x/image/tiff"
	_ "golang.org/x/image/webp"
)

const MaxPixels int64 = 40_000_000

var scalingSlots = make(chan struct{}, 2)

// ScaleContext bounds concurrent resize work and permits cancellation while waiting.
func ScaleContext(ctx context.Context, mediaType string, data []byte, tier Tier, maxOutputBytes int64) (Result, error) {
	select {
	case scalingSlots <- struct{}{}:
		defer func() { <-scalingSlots }()
	case <-ctx.Done():
		return Result{}, ctx.Err()
	}
	result, err := scale(mediaType, data, tier, ImagingLanczos)
	if err != nil {
		return Result{}, err
	}
	if maxOutputBytes > 0 && int64(len(result.Bytes)) > maxOutputBytes {
		return Result{}, fmt.Errorf("resized image exceeds %d bytes (%d bytes)", maxOutputBytes, len(result.Bytes))
	}
	return result, nil
}

type Tier int

const (
	P720 Tier = iota
	P1080
	Original
)

type Engine int

const (
	CatmullRom Engine = iota
	ApproxBiLinear
	ImagingLanczos
	CatmullRomSingle
)

type Result struct {
	MediaType string
	Bytes     []byte
}

type box struct{ width, height int }

func tierEdges(t Tier) (int, int, error) {
	switch t {
	case P720:
		return 1280, 720, nil
	case P1080:
		return 1920, 1080, nil
	case Original:
		return math.MaxInt, math.MaxInt, nil
	default:
		return 0, 0, fmt.Errorf("unknown tier %d", t)
	}
}

func tierBox(t Tier, w, h int) (box, error) {
	longEdge, shortEdge, err := tierEdges(t)
	if err != nil {
		return box{}, err
	}
	switch {
	case w > h:
		return box{longEdge, shortEdge}, nil
	case h > w:
		return box{shortEdge, longEdge}, nil
	default:
		return box{shortEdge, shortEdge}, nil
	}
}

// Scale validates container metadata before allocating decoded pixels, then resizes
// only when required. Engine is exposed solely for benchmark comparison.
func Scale(mediaType string, data []byte, tier Tier, engine Engine) (Result, error) {
	return scale(mediaType, data, tier, engine)
}

func scale(mediaType string, data []byte, tier Tier, engine Engine) (Result, error) {
	if len(data) == 0 {
		return Result{}, errors.New("empty image")
	}
	orientation, err := jpegOrientation(mediaType, data)
	if err != nil {
		return Result{}, err
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return Result{}, fmt.Errorf("cannot parse image: %w", err)
	}
	if !formatMatches(mediaType, format) {
		return Result{}, fmt.Errorf("media type %s does not match format %s", mediaType, format)
	}
	if cfg.Width <= 0 || cfg.Height <= 0 || int64(cfg.Width) > MaxPixels/int64(cfg.Height) {
		return Result{}, fmt.Errorf("image %dx%d exceeds pixel budget %d", cfg.Width, cfg.Height, MaxPixels)
	}
	animated, err := validateAnimation(format, data)
	if err != nil {
		return Result{}, err
	}
	if tier == Original {
		return Result{MediaType: mediaType, Bytes: data}, nil
	}
	w, h := cfg.Width, cfg.Height
	if orientation >= 5 {
		w, h = h, w
	}
	b, err := tierBox(tier, w, h)
	if err != nil {
		return Result{}, err
	}
	if w <= b.width && h <= b.height {
		return Result{MediaType: mediaType, Bytes: data}, nil
	}
	if animated {
		return Result{}, errors.New("animated or multi-frame image cannot be resized")
	}
	decoded, decodedFormat, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return Result{}, fmt.Errorf("cannot decode image: %w", err)
	}
	if decodedFormat != format || decoded.Bounds().Dx() != cfg.Width || decoded.Bounds().Dy() != cfg.Height {
		return Result{}, errors.New("decoded dimensions do not match declaration")
	}
	factor := math.Min(float64(b.width)/float64(w), float64(b.height)/float64(h))
	dw := int(math.Min(float64(b.width), math.Max(1, math.Round(float64(w)*factor))))
	dh := int(math.Min(float64(b.height), math.Max(1, math.Round(float64(h)*factor))))
	src := oriented{src: decoded, orientation: orientation}
	resized, err := resize(src, dw, dh, ImagingLanczos)
	if err != nil {
		return Result{}, err
	}
	alpha := hasAlpha(resized)
	var output bytes.Buffer
	outType := "image/png"
	if !alpha && mediaType == "image/jpeg" {
		outType = "image/jpeg"
		err = jpeg.Encode(&output, toOpaque(resized), &jpeg.Options{Quality: 92})
	} else {
		err = png.Encode(&output, resized)
	}
	if err != nil {
		return Result{}, fmt.Errorf("encode resized image: %w", err)
	}
	return Result{MediaType: outType, Bytes: output.Bytes()}, nil
}

func formatMatches(mediaType, format string) bool {
	switch mediaType {
	case "image/jpeg":
		return format == "jpeg"
	case "image/png":
		return format == "png"
	case "image/gif":
		return format == "gif"
	case "image/webp":
		return format == "webp"
	case "image/bmp":
		return format == "bmp"
	case "image/tiff":
		return format == "tiff"
	default:
		return false
	}
}

// ResizeForBenchmark resamples an already decoded image without encoding it.
func ResizeForBenchmark(src image.Image, width, height int, engine Engine) (image.Image, error) {
	return resize(src, width, height, engine)
}

func resize(src image.Image, width, height int, engine Engine) (image.Image, error) {
	if engine == ImagingLanczos {
		return imaging.Resize(src, width, height, imaging.Lanczos), nil
	}
	if engine == CatmullRomSingle {
		dst := image.NewNRGBA(image.Rect(0, 0, width, height))
		draw.CatmullRom.Scale(dst, dst.Bounds(), src, src.Bounds(), draw.Over, nil)
		return dst, nil
	}
	if engine != CatmullRom && engine != ApproxBiLinear {
		return nil, fmt.Errorf("unknown resize engine %d", engine)
	}
	maxRatio := math.Max(float64(src.Bounds().Dx())/float64(width), float64(src.Bounds().Dy())/float64(height))
	step := 1
	for step*2 <= int(maxRatio) {
		step *= 2
	}
	var filter draw.Interpolator = draw.CatmullRom
	if engine == ApproxBiLinear {
		filter = draw.ApproxBiLinear
	}
	current := src
	if step > 1 {
		w := max(1, int(math.Round(float64(src.Bounds().Dx())/float64(step))))
		h := max(1, int(math.Round(float64(src.Bounds().Dy())/float64(step))))
		tmp := image.NewNRGBA(image.Rect(0, 0, w, h))
		filter.Scale(tmp, tmp.Bounds(), current, current.Bounds(), draw.Over, nil)
		current = tmp
	}
	dst := image.NewNRGBA(image.Rect(0, 0, width, height))
	filter.Scale(dst, dst.Bounds(), current, current.Bounds(), draw.Over, nil)
	return dst, nil
}

func hasAlpha(img image.Image) bool {
	b := img.Bounds()
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			_, _, _, a := img.At(x, y).RGBA()
			if a != 0xffff {
				return true
			}
		}
	}
	return false
}

func toOpaque(img image.Image) *image.RGBA {
	b := img.Bounds()
	dst := image.NewRGBA(image.Rect(0, 0, b.Dx(), b.Dy()))
	for y := 0; y < b.Dy(); y++ {
		for x := 0; x < b.Dx(); x++ {
			r, g, bl, _ := img.At(b.Min.X+x, b.Min.Y+y).RGBA()
			dst.SetRGBA(x, y, color.RGBA{uint8(r >> 8), uint8(g >> 8), uint8(bl >> 8), 255})
		}
	}
	return dst
}

// oriented maps displayed coordinates back to the encoded raster without allocating a rotated copy.
type oriented struct {
	src         image.Image
	orientation int
}

func (o oriented) Bounds() image.Rectangle {
	b := o.src.Bounds()
	if o.orientation >= 5 && o.orientation <= 8 {
		return image.Rect(0, 0, b.Dy(), b.Dx())
	}
	return image.Rect(0, 0, b.Dx(), b.Dy())
}
func (o oriented) ColorModel() color.Model { return o.src.ColorModel() }
func (o oriented) At(x, y int) color.Color {
	b := o.src.Bounds()
	sw, sh := b.Dx(), b.Dy()
	var sx, sy int
	switch o.orientation {
	case 2:
		sx, sy = sw-1-x, y
	case 3:
		sx, sy = sw-1-x, sh-1-y
	case 4:
		sx, sy = x, sh-1-y
	case 5:
		sx, sy = y, x
	case 6:
		sx, sy = y, sh-1-x
	case 7:
		sx, sy = sw-1-y, sh-1-x
	case 8:
		sx, sy = sw-1-y, x
	default:
		sx, sy = x, y
	}
	return o.src.At(b.Min.X+sx, b.Min.Y+sy)
}
