package image

import (
	"encoding/binary"
	"errors"
)

func validateAnimation(format string, data []byte) (bool, error) {
	var animated bool
	var err error
	switch format {
	case "gif":
		animated, err = gifAnimated(data)
	case "png":
		_, err = apngPresent(data) // Java ImageIO exposes APNG as its default static PNG image.
	case "webp":
		animated, err = webpAnimated(data)
	}
	if err != nil {
		return false, err
	}
	return animated, nil
}

func gifAnimated(data []byte) (bool, error) {
	if len(data) < 13 || string(data[:3]) != "GIF" {
		return false, errors.New("malformed GIF header")
	}
	p := 13
	if data[10]&0x80 != 0 {
		p += 3 * (1 << ((data[10] & 7) + 1))
	}
	if p > len(data) {
		return false, errors.New("truncated GIF color table")
	}
	frames := 0
	for p < len(data) {
		switch data[p] {
		case 0x3b:
			return frames > 1, nil
		case 0x21:
			p++
			if p >= len(data) {
				return false, errors.New("truncated GIF extension")
			}
			p++
			var err error
			p, err = skipGIFSubBlocks(data, p)
			if err != nil {
				return false, err
			}
		case 0x2c:
			if p+10 > len(data) {
				return false, errors.New("truncated GIF image descriptor")
			}
			frames++
			packed := data[p+9]
			p += 10
			if packed&0x80 != 0 {
				p += 3 * (1 << ((packed & 7) + 1))
			}
			if p > len(data) || p >= len(data) {
				return false, errors.New("truncated GIF image data")
			}
			p++ // LZW minimum code size.
			var err error
			p, err = skipGIFSubBlocks(data, p)
			if err != nil {
				return false, err
			}
		default:
			return false, errors.New("invalid GIF block marker")
		}
	}
	return false, errors.New("GIF trailer is missing")
}

func skipGIFSubBlocks(data []byte, p int) (int, error) {
	for {
		if p >= len(data) {
			return 0, errors.New("truncated GIF sub-block")
		}
		n := int(data[p])
		p++
		if n == 0 {
			return p, nil
		}
		if p+n > len(data) {
			return 0, errors.New("truncated GIF sub-block data")
		}
		p += n
	}
}

func apngPresent(data []byte) (bool, error) {
	if len(data) < 8 {
		return false, errors.New("truncated PNG header")
	}
	for p := 8; p+12 <= len(data); {
		n := int(binary.BigEndian.Uint32(data[p : p+4]))
		if n < 0 || p+12+n > len(data) {
			return false, errors.New("truncated PNG chunk")
		}
		kind := string(data[p+4 : p+8])
		if kind == "IDAT" {
			return false, nil // APNG control chunks are required before image data.
		}
		if kind == "acTL" {
			if n != 8 {
				return false, errors.New("invalid APNG animation control chunk")
			}
			return true, nil
		}
		p += 12 + n
		if kind == "IEND" {
			return false, nil
		}
	}
	return false, nil
}

func webpAnimated(data []byte) (bool, error) {
	if len(data) < 12 || string(data[:4]) != "RIFF" || string(data[8:12]) != "WEBP" {
		return false, errors.New("malformed WebP RIFF header")
	}
	for p := 12; p+8 <= len(data); {
		n := int(binary.LittleEndian.Uint32(data[p+4 : p+8]))
		if n < 0 || p+8+n > len(data) {
			return false, errors.New("truncated WebP chunk")
		}
		kind := string(data[p : p+4])
		if kind == "VP8X" {
			if n < 10 {
				return false, errors.New("invalid WebP extended header")
			}
			return data[p+8]&0x02 != 0, nil
		}
		p += 8 + n + (n & 1)
	}
	return false, nil
}

// jpegOrientation reads only APP1/Exif and IFD0's 0x0112 SHORT field.
func jpegOrientation(mediaType string, data []byte) (int, error) {
	if mediaType != "image/jpeg" || len(data) < 2 || data[0] != 0xff || data[1] != 0xd8 {
		return 1, nil
	}
	for p := 2; p+4 <= len(data); {
		if data[p] != 0xff {
			return 1, nil
		}
		for p < len(data) && data[p] == 0xff {
			p++
		}
		if p >= len(data) {
			break
		}
		marker := data[p]
		p++
		if marker == 0xda || marker == 0xd9 {
			return 1, nil
		}
		if marker == 0x01 || marker >= 0xd0 && marker <= 0xd7 {
			continue
		}
		if p+2 > len(data) {
			return 0, errors.New("truncated JPEG segment")
		}
		n := int(binary.BigEndian.Uint16(data[p : p+2]))
		if n < 2 || p+n > len(data) {
			return 0, errors.New("invalid JPEG segment length")
		}
		segment := data[p+2 : p+n]
		if marker == 0xe1 && len(segment) >= 6 && string(segment[:6]) == "Exif\x00\x00" {
			return parseOrientation(segment[6:])
		}
		p += n
	}
	return 1, nil
}

func parseOrientation(tiff []byte) (int, error) {
	bad := func() (int, error) { return 0, errors.New("malformed EXIF orientation") }
	if len(tiff) < 8 {
		return bad()
	}
	var order binary.ByteOrder
	switch string(tiff[:2]) {
	case "II":
		order = binary.LittleEndian
	case "MM":
		order = binary.BigEndian
	default:
		return bad()
	}
	if order.Uint16(tiff[2:4]) != 42 {
		return bad()
	}
	offset := int(order.Uint32(tiff[4:8]))
	if offset < 8 || offset+2 > len(tiff) {
		return bad()
	}
	count := int(order.Uint16(tiff[offset : offset+2]))
	if count > (len(tiff)-offset-2)/12 {
		return bad()
	}
	for i := 0; i < count; i++ {
		entry := tiff[offset+2+i*12 : offset+2+(i+1)*12]
		if order.Uint16(entry[:2]) != 0x0112 {
			continue
		}
		if order.Uint16(entry[2:4]) != 3 || order.Uint32(entry[4:8]) != 1 {
			return bad()
		}
		value := int(order.Uint16(entry[8:10]))
		if value < 1 || value > 8 {
			return bad()
		}
		return value, nil
	}
	return 1, nil
}
