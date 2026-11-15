package protocol

import (
	"os"
	"path/filepath"
	"testing"

	"google.golang.org/protobuf/proto"
)

// readyWithZone builds the smallest READY envelope that reaches host time-zone validation.
func readyWithZone(zone string) *Envelope {
	return testEnvelope(MessageType_READY, "", &Envelope_Ready{Ready: &Ready{
		DaemonVersion: proto.String("1.0.0"),
		HostInfo:      &HostInfo{OperatingSystem: OperatingSystem_LINUX, Architecture: Architecture_AMD64, TimeZone: proto.String(zone)},
		RuntimeStatus: &RuntimeSnapshot{Revision: proto.Int64(1), Status: &RuntimeBody{DaemonVersion: proto.String("1.0.0"), Configuration: &ConfigurationStatus{AppliedVersion: proto.Int64(0)}}},
	}})
}

// The host time zone must be an identifier Java's ZoneId resolves, so the control server accepts the
// READY the daemon publishes on any host.
func TestValidateHostAcceptsExactlyJavaZoneIDs(t *testing.T) {
	for _, zone := range []string{
		"UTC",
		"UTC+00:00",
		"UTC-00:00",
		"UTC+08:00",
		"UTC-03:30",
		"UTC+05:45",
		"UTC+18:00",
		"UTC-18:00",
		"Asia/Shanghai",
		"America/New_York",
		"America/Argentina/Buenos_Aires",
		"Europe/Berlin",
		"Etc/GMT+5",
		"US/Pacific",
	} {
		if _, err := MarshalEnvelope(readyWithZone(zone)); err != nil {
			t.Errorf("time zone %q rejected: %v", zone, err)
		}
	}
	for _, zone := range []string{
		"Local",
		"CST",
		"posixrules",
		"localtime",
		"Factory",
		"UTC+19:00",
		"UTC-19:00",
		"UTC+18:01",
		"UTC+8",
		"UTC+08:0",
		"UTC+0:00",
		"GMT+8",
		"Mars/Olympus",
		"Asia/shanghai",
		"asia/Shanghai",
		"ASIA/Shanghai",
		"Asia/SHANGHAI",
		// posix/ and right/ name zoneinfo layout directories; Java rejects them even though the
		// location database resolves the files behind them.
		"posix/Europe/Berlin",
		"right/Europe/Berlin",
		"posix/Asia/Shanghai",
		"",
	} {
		if _, err := MarshalEnvelope(readyWithZone(zone)); err == nil {
			t.Errorf("time zone %q accepted", zone)
		}
	}
	if err := validateHost(&HostInfo{OperatingSystem: OperatingSystem_LINUX, Architecture: Architecture_AMD64}); err != nil {
		t.Fatalf("omitted time zone rejected: %v", err)
	}
}

// Zone identifiers retain exact directory-entry spelling even on a case-insensitive filesystem.
func TestExactZonePath(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "Asia"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "Asia", "Shanghai"), []byte("fixture"), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"Asia/Shanghai", "Asia/shanghai", "asia/Shanghai", "Asia/Missing", "Missing/Shanghai", "Asia/Shanghai/Child"} {
		if got := exactZonePath(root, name); got != (name == "Asia/Shanghai") {
			t.Errorf("exactZonePath(%q) = %t", name, got)
		}
	}
}
