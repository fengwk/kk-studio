package protocol

// EncodeEnvelopeFrame validates, encodes, and length-prefixes a control envelope.
func EncodeEnvelopeFrame(envelope *Envelope) ([]byte, error) {
	payload, err := MarshalEnvelope(envelope)
	if err != nil {
		return nil, err
	}
	return EncodeFrame(payload)
}

// DecodeEnvelopeFrame removes one exact frame and validates its protobuf envelope.
func DecodeEnvelopeFrame(frame []byte) (*Envelope, error) {
	payload, err := DecodeFrame(frame)
	if err != nil {
		return nil, err
	}
	return UnmarshalEnvelope(payload)
}
