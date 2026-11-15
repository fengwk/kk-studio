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
	result, err := scale(mediaType, data, tier)
	if err != nil {
		return Result{}, err
	}
	if result.Resized && maxOutputBytes > 0 && int64(len(result.Bytes)) > maxOutputBytes {
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

type Result struct {
	MediaType      string
	Bytes          []byte
	OriginalWidth  int
	OriginalHeight int
	DisplayWidth   int
	DisplayHeight  int
	Resized        bool
}

type box struct{ width, height int }

// container is what the headers of an encoded image declare; no pixel is decoded to build it.
type container struct {
	metadata    Result
	format      string
	orientation int
	animated    bool
}

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

// scale validates the container metadata before any pixel is allocated and returns the encoded
// bytes unchanged unless the requested tier needs a smaller raster.
func scale(mediaType string, data []byte, tier Tier) (Result, error) {
	source, err := inspectImage(mediaType, data)
	if err != nil {
		return Result{}, err
	}
	bounds, err := tierBox(tier, source.metadata.DisplayWidth, source.metadata.DisplayHeight)
	if err != nil {
		return Result{}, err
	}
	if tier == Original || fitsWithin(source.metadata, bounds) {
		source.metadata.MediaType, source.metadata.Bytes = mediaType, data
		return source.metadata, nil
	}
	if source.animated {
		return Result{}, errors.New("animated or multi-frame image cannot be resized")
	}
	resized, err := resizeToBox(data, source, bounds)
	if err != nil {
		return Result{}, err
	}
	encoded, outType, err := encodeResized(mediaType, resized)
	if err != nil {
		return Result{}, err
	}
	source.metadata.MediaType, source.metadata.Bytes, source.metadata.Resized = outType, encoded, true
	source.metadata.DisplayWidth, source.metadata.DisplayHeight = resized.Bounds().Dx(), resized.Bounds().Dy()
	return source.metadata, nil
}

// inspectImage reads the container metadata of an encoded image and refuses anything that is not
// a supported, bounded single image.
func inspectImage(mediaType string, data []byte) (container, error) {
	if len(data) == 0 {
		return container{}, errors.New("empty image")
	}
	orientation, err := jpegOrientation(mediaType, data)
	if err != nil {
		return container{}, err
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return container{}, fmt.Errorf("cannot parse image: %w", err)
	}
	if !formatMatches(mediaType, format) {
		return container{}, fmt.Errorf("media type %s does not match format %s", mediaType, format)
	}
	if cfg.Width <= 0 || cfg.Height <= 0 || int64(cfg.Width) > MaxPixels/int64(cfg.Height) {
		return container{}, fmt.Errorf("image %dx%d exceeds pixel budget %d", cfg.Width, cfg.Height, MaxPixels)
	}
	animated, err := validateAnimation(format, data)
	if err != nil {
		return container{}, err
	}
	displayWidth, displayHeight := cfg.Width, cfg.Height
	if orientation >= 5 {
		displayWidth, displayHeight = displayHeight, displayWidth
	}
	return container{
		metadata:    Result{OriginalWidth: cfg.Width, OriginalHeight: cfg.Height, DisplayWidth: displayWidth, DisplayHeight: displayHeight},
		format:      format,
		orientation: orientation,
		animated:    animated,
	}, nil
}

func fitsWithin(metadata Result, bounds box) bool {
	return metadata.DisplayWidth <= bounds.width && metadata.DisplayHeight <= bounds.height
}

// resizeToBox decodes the pixels and scales them into the tier box, honouring EXIF orientation.
func resizeToBox(data []byte, source container, bounds box) (image.Image, error) {
	decoded, format, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, fmt.Errorf("cannot decode image: %w", err)
	}
	if format != source.format || decoded.Bounds().Dx() != source.metadata.OriginalWidth || decoded.Bounds().Dy() != source.metadata.OriginalHeight {
		return nil, errors.New("decoded dimensions do not match declaration")
	}
	width, height := fitDimensions(source.metadata.DisplayWidth, source.metadata.DisplayHeight, bounds)
	return imaging.Resize(oriented{src: decoded, orientation: source.orientation}, width, height, imaging.Lanczos), nil
}

// fitDimensions scales the displayed size down into bounds, keeping the aspect ratio and never
// rounding a side to zero.
func fitDimensions(width, height int, bounds box) (int, int) {
	factor := math.Min(float64(bounds.width)/float64(width), float64(bounds.height)/float64(height))
	scaledWidth := int(math.Min(float64(bounds.width), math.Max(1, math.Round(float64(width)*factor))))
	scaledHeight := int(math.Min(float64(bounds.height), math.Max(1, math.Round(float64(height)*factor))))
	return scaledWidth, scaledHeight
}

// encodeResized encodes a resized image as PNG, or as JPEG when the source was an opaque JPEG.
func encodeResized(mediaType string, resized image.Image) ([]byte, string, error) {
	alpha := hasAlpha(resized)
	var output bytes.Buffer
	outType := "image/png"
	var err error
	if !alpha && mediaType == "image/jpeg" {
		outType = "image/jpeg"
		err = jpeg.Encode(&output, toOpaque(resized), &jpeg.Options{Quality: 92})
	} else {
		err = png.Encode(&output, resized)
	}
	if err != nil {
		return nil, "", fmt.Errorf("encode resized image: %w", err)
	}
	return output.Bytes(), outType, nil
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
