package protocol

import (
	"fmt"
)

func validateResult(r *CapabilityResult, path string, allowResource bool) error {
	if r == nil {
		return invalid(path + ".result is required")
	}
	if err := requireString(r.CallId, path+".result.callId", true, 256); err != nil {
		return err
	}
	if r.Error == nil {
		return invalid(path + ".result.error is required")
	}
	if err := jsonField(r.GetDetailsJson(), path+".result.detailsJson", 1<<20, true); err != nil {
		return err
	}
	if len(r.Contents) > 64 {
		return invalid(path + ".result.contents must not exceed 64 items")
	}
	for i, c := range r.Contents {
		if c == nil {
			return invalid(fmt.Sprintf("%s.result.contents[%d] must declare exactly one content body", path, i))
		}
		switch b := c.Body.(type) {
		case *Content_Text:
			if b.Text == nil {
				return invalid(path + ".result.content.text must be a string")
			}
		case *Content_Json:
			if b.Json == nil {
				return invalid(path + ".result.content.json is required")
			}
			if err := jsonField(c.GetJson().GetJson(), path+".result.content.json", 1<<20, false); err != nil {
				return err
			}
		case *Content_Resource:
			if !allowResource {
				return invalid(path + ".result.contents must not declare a resource")
			}
			if err := validateResourceContent(b.Resource, path+".result.contents.resource"); err != nil {
				return err
			}
		default:
			return invalid(path + ".result.contents must declare exactly one content body")
		}
	}
	return nil
}

func validateResourceContent(r *ResourceContent, path string) error {
	if r == nil {
		return invalid(path + " is required")
	}
	if err := uuid(r.GetUploadId(), path+".uploadId"); err != nil {
		return err
	}
	if err := mediaType(r.GetMediaType(), path+".mediaType"); err != nil {
		return err
	}
	if r.Name != nil {
		if err := requireBytesString(r.Name, path+".name", true, 512); err != nil {
			return err
		}
	}
	if r.Name != nil && hasControl(r.GetName()) {
		return invalid(path + ".name must not contain control characters")
	}
	if r.Size == nil || r.GetSize() < 0 || r.GetSize() > maxSafeInteger {
		return invalid(path + ".size must be a non-negative safe integer")
	}
	if err := sha(r.GetSha256(), path+".sha256"); err != nil {
		return err
	}
	if len(r.GetPreview()) > 16384 {
		return invalid(path + ".preview exceeds 16384 UTF-8 bytes")
	}
	return nil
}
