package config

import (
	"bytes"
	jsonv2 "encoding/json/v2"
	"strconv"
)

// SerializeDaemonCache returns the private bootstrap cache, omitting unset optional fields and listing LSP servers in declaration order.
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
	if err := appendLSPCache(&output, value.LSP); err != nil {
		return nil, err
	}
	output.WriteString(`,"proxyUrl":`)
	if err := appendJSON(&output, value.ProxyURL); err != nil {
		return nil, err
	}
	output.WriteString(`}}`)
	return output.Bytes(), nil
}

// appendLSPCache writes the lsp object, whose empty state is an explicit null.
func appendLSPCache(output *bytes.Buffer, servers []LSPServerConfig) error {
	if len(servers) == 0 {
		output.WriteString(`null`)
		return nil
	}
	output.WriteString(`{"servers":{`)
	for index, server := range servers {
		if index > 0 {
			output.WriteByte(',')
		}
		if err := appendLSPServerCache(output, server); err != nil {
			return err
		}
	}
	output.WriteString(`}}`)
	return nil
}

// appendLSPServerCache writes the required command/extensions and omits empty marker lists.
func appendLSPServerCache(output *bytes.Buffer, server LSPServerConfig) error {
	if err := appendJSON(output, server.ID); err != nil {
		return err
	}
	output.WriteString(`:{"command":`)
	if err := appendJSON(output, server.Command); err != nil {
		return err
	}
	output.WriteString(`,"extensions":`)
	if err := appendJSON(output, server.Extensions); err != nil {
		return err
	}
	for _, field := range []struct {
		name   string
		values []string
	}{{"rootMarkers", server.RootMarkers}, {"firstMatchMarkers", server.FirstMatchMarkers}} {
		if len(field.values) == 0 {
			continue
		}
		output.WriteString(`,"` + field.name + `":`)
		if err := appendJSON(output, field.values); err != nil {
			return err
		}
	}
	output.WriteByte('}')
	return nil
}

func appendJSON(output *bytes.Buffer, value any) error {
	data, err := jsonv2.Marshal(value)
	if err != nil {
		return err
	}
	output.Write(data)
	return nil
}
