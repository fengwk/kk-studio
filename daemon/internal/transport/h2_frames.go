package transport

import "encoding/binary"

const http2ClientPreface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"

type h2FrameObserver struct {
	prefaceRead      int
	header           [9]byte
	headerRead       int
	payloadRemaining uint32
	frameType        byte
	streamID         uint32
	goawayData       [8]byte
	goawayRead       int
}

func (o *h2FrameObserver) feed(data []byte, onGoaway func(uint32)) {
	for len(data) > 0 {
		if o.prefaceRead < len(http2ClientPreface) {
			want := http2ClientPreface[o.prefaceRead]
			if data[0] != want {
				o.prefaceRead = len(http2ClientPreface)
				continue
			}
			o.prefaceRead++
			data = data[1:]
			continue
		}
		if o.payloadRemaining == 0 {
			n := copy(o.header[o.headerRead:], data)
			o.headerRead += n
			data = data[n:]
			if o.headerRead < len(o.header) {
				continue
			}
			length := uint32(o.header[0])<<16 | uint32(o.header[1])<<8 | uint32(o.header[2])
			o.frameType = o.header[3]
			o.streamID = binary.BigEndian.Uint32(o.header[5:9]) & 0x7fffffff
			o.payloadRemaining = length
			o.headerRead = 0
			o.goawayRead = 0
			if length == 0 {
				o.payloadRemaining = 0
				continue
			}
		}
		consume := len(data)
		if uint32(consume) > o.payloadRemaining {
			consume = int(o.payloadRemaining)
		}
		if o.frameType == 7 && o.streamID == 0 && o.payloadRemaining >= 8 && o.goawayRead < 8 {
			need := 8 - o.goawayRead
			if consume > need {
				consume = need
			}
			o.goawayRead += copy(o.goawayData[o.goawayRead:], data[:consume])
			if o.goawayRead == 8 && onGoaway != nil {
				onGoaway(binary.BigEndian.Uint32(o.goawayData[4:8]))
			}
		}
		data = data[consume:]
		o.payloadRemaining -= uint32(consume)
	}
}
