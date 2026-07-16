package mobilecore

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	xraynet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
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

func TestURLTestUsesDedicatedInboundAndHead(t *testing.T) {
	method := ""
	server := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, request *http.Request) {
		method = request.Method
	}))
	defer server.Close()

	inboundTag := ""
	elapsed, err := runURLTest(server.URL, 1000, func(ctx context.Context, destination xraynet.Destination) (net.Conn, error) {
		if inbound := session.InboundFromContext(ctx); inbound != nil {
			inboundTag = inbound.Tag
		}
		return (&net.Dialer{}).DialContext(ctx, "tcp", destination.NetAddr())
	})
	if err != nil {
		t.Fatal(err)
	}
	if elapsed < 0 {
		t.Fatalf("elapsed = %d, want >= 0", elapsed)
	}
	if method != http.MethodHead {
		t.Fatalf("method = %q, want HEAD", method)
	}
	if inboundTag != latencyTestInboundTag {
		t.Fatalf("inbound tag = %q, want %q", inboundTag, latencyTestInboundTag)
	}
}

func TestURLTestRejectsInvalidArguments(t *testing.T) {
	if _, err := UrlTest("not-a-url", 1000); err == nil {
		t.Fatal("UrlTest accepted an invalid URL")
	}
	if _, err := UrlTest("https://example.com", 0); err == nil {
		t.Fatal("UrlTest accepted an invalid timeout")
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
