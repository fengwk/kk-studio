package config

import (
	"bytes"
	jsonv2 "encoding/json/v2"
	"strconv"
)

// SerializeDaemonCache returns the private bootstrap cache with TypeScript-compatible field omission and LSP declaration order.
func SerializeDaemonCache(value DaemonConfig) ([]byte, error) {
	var output bytes.Buffer
	output.WriteString(`{"environmentId":`)
	if err := appendJSON(&output, value.EnvironmentID); err != nil {
		return nil, err
	}
	output.WriteString(`,"version":`)
	if err := appendJSON(&output, strconv.FormatInt(value.ConfigVersion, 10)); err != nil {
		return nil, err
	}
	output.WriteString(`,"daemonConfig":{"studioUrl":`)
	if err := appendJSON(&output, value.StudioURL); err != nil {
		return nil, err
	}
	output.WriteString(`,"bashExecutable":`)
	if err := appendJSON(&output, value.BashExecutableSetting); err != nil {
		return nil, err
	}
	output.WriteString(`,"lsp":`)
	if len(value.LSP) == 0 {
		output.WriteString(`null`)
	} else {
		output.WriteString(`{"servers":{`)
		for index, server := range value.LSP {
			if index > 0 {
				output.WriteByte(',')
			}
			if err := appendJSON(&output, server.ID); err != nil {
				return nil, err
			}
			output.WriteString(`:{"command":`)
			if err := appendJSON(&output, server.Command); err != nil {
				return nil, err
			}
			if len(server.Extensions) > 0 {
				output.WriteString(`,"extensions":`)
				if err := appendJSON(&output, server.Extensions); err != nil {
					return nil, err
				}
			}
			if len(server.RootMarkers) > 0 {
				output.WriteString(`,"rootMarkers":`)
				if err := appendJSON(&output, server.RootMarkers); err != nil {
					return nil, err
				}
			}
			if len(server.FirstMatchMarkers) > 0 {
				output.WriteString(`,"firstMatchMarkers":`)
				if err := appendJSON(&output, server.FirstMatchMarkers); err != nil {
					return nil, err
				}
			}
			output.WriteByte('}')
		}
		output.WriteString(`}}`)
	}
	output.WriteString(`,"proxyUrl":`)
	if err := appendJSON(&output, value.ProxyURL); err != nil {
		return nil, err
	}
	output.WriteString(`}}`)
	return output.Bytes(), nil
}

func appendJSON(output *bytes.Buffer, value any) error {
	data, err := jsonv2.Marshal(value)
	if err != nil {
		return err
	}
	output.Write(data)
	return nil
}
