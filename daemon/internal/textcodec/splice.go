package textcodec

import (
	"bufio"
	"bytes"
	"errors"
	"unicode/utf8"

	"golang.org/x/text/transform"
)

// Replacement describes a changed span in decoded UTF-8 byte coordinates.
type Replacement struct {
	Start, End int
	Text       string
}

var ErrUnsafeSplice = errors.New("the replacement cannot preserve the surrounding bytes; use write to rewrite the whole file")

// Splice keeps the BOM and untouched bytes verbatim. The replacements must be sorted and
// non-overlapping; decoded and enc must describe original as returned by Decode.
func Splice(path string, original, decoded []byte, enc Encoding, replacements []Replacement, updated string) ([]byte, error) {
	offsets, err := spliceOffsets(original, decoded, enc, replacements)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 0, len(original))
	previous := 0
	for i, replacement := range replacements {
		start, end := offsets[2*i], offsets[2*i+1]
		body, err := encodeBody(replacement.Text, enc)
		if err != nil {
			return nil, err
		}
		out = append(out, original[previous:start]...)
		out = append(out, body...)
		previous = end
	}
	out = append(out, original[previous:]...)
	text, detected, _, err := Decode(path, out)
	if err != nil || detected.Name != enc.Name || detected.BOM != enc.BOM || string(text) != updated {
		return nil, ErrUnsafeSplice
	}
	return out, nil
}

// spliceOffsets maps only the requested boundaries, without a per-character offset table.
func spliceOffsets(original, decoded []byte, enc Encoding, replacements []Replacement) ([]int, error) {
	prefix := len(bomPrefix(enc))
	body := original[prefix:]
	offsets := make([]int, 0, 2*len(replacements))
	for _, replacement := range replacements {
		offsets = append(offsets, replacement.Start, replacement.End)
	}
	if enc.Name == "UTF-8" && bytes.Equal(decoded, body) {
		for i := range offsets {
			offsets[i] += prefix
		}
		return offsets, nil
	}

	next := 0
	record := func(text, raw int) {
		for next < len(offsets) && offsets[next] == text {
			offsets[next] = prefix + raw
			next++
		}
	}
	record(0, 0)
	if isWideName(enc.Name) || enc.Name == "UTF-8" || strictASCII(enc.Name) {
		raw := bytes.NewReader(body)
		reader := bufio.NewReader(raw)
		var source runeSource = newUTF8Source(reader, strictASCII(enc.Name))
		var wide *wideSource
		if isWideName(enc.Name) {
			wide = newWideSource(reader, enc)
			source = wide
		}
		for text := 0; next < len(offsets); {
			value, err := source.readRune()
			if err != nil {
				break
			}
			text += utf8.RuneLen(value)
			consumed := len(body) - raw.Len() - reader.Buffered()
			if wide != nil {
				// A high surrogate may have read ahead into the next decoded unit.
				if wide.hasNext {
					consumed -= wide.width
				} else if wide.short {
					consumed-- // Only UTF-16 reads ahead; a short unit is one byte.
				}
			}
			record(text, consumed)
		}
	} else {
		xtext, err := encodingByName(enc.Name)
		if err != nil || xtext == nil {
			return nil, ErrUnsafeSplice
		}
		decoder := xtext.NewDecoder()
		// Expose only enough input for the next decoded unit. Escape sequences that
		// emit no text stay with the bytes up to the next decoded boundary.
		var dst [2 * utf8.UTFMax]byte
		for raw, limit, text := 0, 0, 0; next < len(offsets) && raw < len(body); {
			if limit == raw {
				limit++
			}
			_, size := utf8.DecodeRune(decoded[text:])
			written, consumed, err := decoder.Transform(dst[:size], body[raw:limit], limit == len(body))
			if err == transform.ErrShortDst && written == 0 && consumed == 0 {
				// An indivisible encoded unit may produce more than one rune.
				written, consumed, err = decoder.Transform(dst[:], body[raw:limit], limit == len(body))
			}
			if !bytes.Equal(dst[:written], decoded[text:min(text+written, len(decoded))]) {
				return nil, ErrUnsafeSplice
			}
			raw += consumed
			if written != 0 {
				text += written
				record(text, raw)
			}
			switch {
			case err == transform.ErrShortSrc && limit < len(body):
				limit++
			case (err == nil || err == transform.ErrShortDst) && consumed > 0:
			default:
				return nil, ErrUnsafeSplice
			}
		}
	}
	if next != len(offsets) {
		return nil, ErrUnsafeSplice
	}
	return offsets, nil
}
