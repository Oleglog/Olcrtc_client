package mobilecore

import (
	"os"
	"testing"
)

func TestRejectsInvalidXrayConfig(t *testing.T) {
	if err := StartXray(t.TempDir(), "{"); err == nil {
		t.Fatal("StartXray accepted invalid JSON")
	}
	if IsXrayRunning() {
		t.Fatal("Xray remained running after invalid config")
	}
}

func TestStartXraySetsAssetDirectory(t *testing.T) {
	directory := t.TempDir()
	if err := StartXray(directory, "{"); err == nil {
		t.Fatal("StartXray accepted invalid JSON")
	}
	if value := os.Getenv("xray.location.asset"); value != directory {
		t.Fatalf("xray.location.asset = %q, want %q", value, directory)
	}
}

func TestStartXrayRequiresAssetDirectory(t *testing.T) {
	if err := StartXray("", "{}"); err == nil {
		t.Fatal("StartXray accepted an empty asset directory")
	}
}

func TestValidateXrayConfigRequiresAssetDirectory(t *testing.T) {
	if err := ValidateXrayConfig("", "{}"); err == nil {
		t.Fatal("ValidateXrayConfig accepted an empty asset directory")
	}
}

func TestStopXrayIsIdempotent(t *testing.T) {
	if err := StopXray(); err != nil {
		t.Fatalf("StopXray returned an error: %v", err)
	}
}

func TestWaitXrayReadyRejectsInvalidArguments(t *testing.T) {
	if err := WaitXrayReady(0, 100); err == nil {
		t.Fatal("WaitXrayReady accepted an invalid port")
	}
	if err := WaitXrayReady(1080, 0); err == nil {
		t.Fatal("WaitXrayReady accepted an invalid timeout")
	}
}

func TestFatalErrors(t *testing.T) {
	fatal := []string{
		"load Xray config: invalid JSON",
		"create Xray instance: invalid outbound",
		"wait for olcRTC: failed to create link: carrier auth failed: unauthorized",
		"SOCKS5 authentication failed",
		"chacha20poly1305: message authentication failed",
	}
	for _, message := range fatal {
		if !IsFatalError(message) {
			t.Fatalf("IsFatalError(%q) = false", message)
		}
	}

	retryable := []string{
		"start Xray instance: network unreachable",
		"wait for Xray SOCKS: connection refused",
		"wait for olcRTC: context deadline exceeded",
		"invalid VPN datapath response",
	}
	for _, message := range retryable {
		if IsFatalError(message) {
			t.Fatalf("IsFatalError(%q) = true", message)
		}
	}
}
