package config

// DaemonConfig is the immutable applied daemon configuration snapshot shared with capabilities.
type DaemonConfig struct {
	EnvironmentID         string
	ConfigVersion         int64
	ConfigPath            string
	DataDir               string
	TokenPath             string
	StudioURL             string
	GatewayURL            string
	BashExecutable        *string
	BashExecutableSetting *string
	// LoginEnvironmentDigest tracks the runtime sample without retaining it in the configuration.
	LoginEnvironmentDigest [32]byte
	LSP                    []LSPServerConfig
	ProxyURL               *string
	DistributionDir        string
}

// LSPServerConfig contains one configured language server and its discovery rules.
// DaemonConfig.LSP keeps declaration order because the first matching server wins.
type LSPServerConfig struct {
	ID                string
	Command           []string
	Extensions        []string
	RootMarkers       []string
	FirstMatchMarkers []string
}
