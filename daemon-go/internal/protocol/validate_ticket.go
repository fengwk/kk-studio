package protocol

import (
	"strings"
)

func validateUploadTicket(t *ResourceUploadTicket) error {
	if err := uuid(t.GetTransferId(), "uploadTicket.transferId"); err != nil {
		return err
	}
	switch t.State {
	case TicketState_TICKET_PENDING:
		if t.UploadId == nil || t.PresignedPut == nil || t.Message != nil {
			return invalid("PENDING ticket requires uploadId and presignedPut only")
		}
		if err := uuid(t.GetUploadId(), "uploadTicket.uploadId"); err != nil {
			return err
		}
		p := t.PresignedPut
		if p.GetMethod() != "PUT" {
			return invalid("presignedPut.method must be PUT")
		}
		if err := requireString(p.Url, "presignedPut.url", true, 4096); err != nil {
			return err
		}
		if len(p.Headers) > 16 {
			return invalid("presignedPut.headers must not exceed 16 entries")
		}
		for k, v := range p.Headers {
			if strings.TrimSpace(k) == "" || len(k) > 1024 || strings.TrimSpace(v) == "" || len(v) > 1024 {
				return invalid("presignedPut.header is invalid")
			}
		}
		if p.ExpiresAtEpochMs == nil || p.GetExpiresAtEpochMs() <= 0 {
			return invalid("presignedPut.expiresAtEpochMs must be positive")
		}
	case TicketState_TICKET_READY:
		if t.UploadId == nil || t.PresignedPut != nil || t.Message != nil {
			return invalid("READY ticket requires uploadId only")
		}
		return uuid(t.GetUploadId(), "uploadTicket.uploadId")
	case TicketState_TICKET_FAILED:
		if t.UploadId != nil || t.PresignedPut != nil {
			return invalid("FAILED ticket requires a message only")
		}
		return requireString(t.Message, "uploadTicket.message", true, 512)
	default:
		return invalid("uploadTicket.state is required")
	}
	return nil
}

func validateDownloadTicket(t *ResourceDownloadTicket) error {
	if err := uuid(t.GetTransferId(), "downloadTicket.transferId"); err != nil {
		return err
	}
	switch t.State {
	case TicketState_TICKET_READY:
		if t.Message != nil {
			return invalid("READY download ticket must not declare a message")
		}
		if t.PresignedGet == nil {
			return invalid("downloadTicket.presignedGet is required")
		}
		if t.PresignedGet.GetMethod() != "GET" {
			return invalid("presignedGet.method must be GET")
		}
		if err := requireString(t.PresignedGet.Url, "presignedGet.url", true, 4096); err != nil {
			return err
		}
		if t.PresignedGet.ExpiresAtEpochMs == nil || t.PresignedGet.GetExpiresAtEpochMs() <= 0 {
			return invalid("presignedGet.expiresAtEpochMs must be positive")
		}
		if t.Size == nil || t.GetSize() < 0 || t.GetSize() > maxSafeInteger {
			return invalid("downloadTicket.size must be non-negative")
		}
		return sha(t.GetSha256(), "downloadTicket.sha256")
	case TicketState_TICKET_FAILED:
		if t.PresignedGet != nil || t.Size != nil || t.Sha256 != nil {
			return invalid("FAILED download ticket requires a message only")
		}
		return requireString(t.Message, "downloadTicket.message", true, 512)
	default:
		return invalid("downloadTicket.state is required")
	}
}
