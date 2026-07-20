package mobilecore

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"syscall"
	"time"

	olcrtc "github.com/openlibrecommunity/olcrtc/mobile"
	xraynet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/transport/internet"
)

var (
	errAlreadyRunning  = errors.New("mobilecore already running")
	errNotRunning      = errors.New("mobilecore is not running")
	mu                 sync.Mutex
	protector          SocketProtector
	xrayInstance       *core.Instance
	profileProbe       *core.Instance
	profileProbeOlcrtc bool
	profileProbeMu     sync.Mutex
)

type SocketProtector interface {
	Protect(fd int) bool
}

type LogWriter interface {
	WriteLog(message string)
}

func init() {
	_ = internet.RegisterDialerController(protectSocket)
}

func SetProtector(value SocketProtector) {
	mu.Lock()
	protector = value
	mu.Unlock()
	olcrtc.SetProtector(value)
}

func SetLogWriter(value LogWriter) {
	olcrtc.SetLogWriter(value)
}

func StartXray(assetDirectory string, configJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	if xrayInstance != nil {
		return errAlreadyRunning
	}
	instance, err := newXrayInstance(assetDirectory, configJSON)
	if err != nil {
		return err
	}
	xrayInstance = instance
	return nil
}

func ValidateXrayConfig(assetDirectory string, configJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	if err := setXrayAssetDirectory(assetDirectory); err != nil {
		return err
	}
	if _, err := core.LoadConfig("json", bytes.NewBufferString(configJSON)); err != nil {
		return fmt.Errorf("load Xray config: %w", err)
	}
	return nil
}

func setXrayAssetDirectory(assetDirectory string) error {
	if strings.TrimSpace(assetDirectory) == "" {
		return errors.New("Xray asset directory is required")
	}
	if err := os.Setenv("xray.location.asset", assetDirectory); err != nil {
		return fmt.Errorf("set Xray asset directory: %w", err)
	}
	return nil
}

func StopXray() error {
	mu.Lock()
	if xrayInstance == nil {
		mu.Unlock()
		return nil
	}

	instance := xrayInstance
	xrayInstance = nil
	mu.Unlock()
	if err := instance.Close(); err != nil {
		return fmt.Errorf("stop Xray instance: %w", err)
	}
	return nil
}

func IsXrayRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return xrayInstance != nil
}

func WaitXrayReady(socksPort int, timeoutMillis int) error {
	if socksPort < 1 || socksPort > 65535 {
		return errors.New("invalid Xray SOCKS port")
	}
	if timeoutMillis <= 0 {
		return errors.New("Xray readiness timeout must be positive")
	}

	deadline := time.Now().Add(time.Duration(timeoutMillis) * time.Millisecond)
	address := fmt.Sprintf("127.0.0.1:%d", socksPort)
	for {
		if !IsXrayRunning() {
			return errNotRunning
		}
		conn, err := net.DialTimeout("tcp", address, 100*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("wait for Xray SOCKS: %w", err)
		}
		time.Sleep(25 * time.Millisecond)
	}
}

func UrlTest(link string, timeoutMillis int) (int64, error) {
	if err := validateURLTest(link, timeoutMillis); err != nil {
		return 0, err
	}

	mu.Lock()
	instance := xrayInstance
	mu.Unlock()
	if instance == nil {
		return 0, errNotRunning
	}

	return runURLTest(link, timeoutMillis, func(ctx context.Context, destination xraynet.Destination) (net.Conn, error) {
		return core.Dial(ctx, instance, destination)
	})
}

func StartProfileProbe(assetDirectory string, configJSON string) error {
	mu.Lock()
	defer mu.Unlock()
	if profileProbe != nil {
		return errAlreadyRunning
	}
	instance, err := newXrayInstance(assetDirectory, configJSON)
	if err != nil {
		return err
	}
	profileProbe = instance
	return nil
}

func ProfileProbeUrlTest(link string, timeoutMillis int, inboundTag string) (int64, error) {
	if err := validateURLTest(link, timeoutMillis); err != nil {
		return 0, err
	}
	if strings.TrimSpace(inboundTag) == "" {
		return 0, errors.New("profile probe inbound tag is required")
	}
	mu.Lock()
	instance := profileProbe
	mu.Unlock()
	if instance == nil {
		return 0, errNotRunning
	}
	return runURLTestWithInboundTag(link, timeoutMillis, inboundTag, func(ctx context.Context, destination xraynet.Destination) (net.Conn, error) {
		return core.Dial(ctx, instance, destination)
	})
}

func validateURLTest(link string, timeoutMillis int) error {
	if timeoutMillis <= 0 {
		return errors.New("URL test timeout must be positive")
	}
	parsed, err := url.ParseRequestURI(link)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return errors.New("URL test requires an HTTP(S) URL")
	}
	return nil
}

func StartProfileProbeOlcrtc(
	provider string,
	transport string,
	roomID string,
	clientID string,
	keyHex string,
	authToken string,
	dnsServer string,
	vp8FPS int,
	vp8BatchSize int,
	keepaliveSeconds int,
	socksPort int,
) error {
	profileProbeMu.Lock()
	defer profileProbeMu.Unlock()
	if err := StartOlcrtc(
		provider,
		transport,
		roomID,
		clientID,
		keyHex,
		authToken,
		dnsServer,
		vp8FPS,
		vp8BatchSize,
		keepaliveSeconds,
		socksPort,
	); err != nil {
		return err
	}
	mu.Lock()
	profileProbeOlcrtc = true
	mu.Unlock()
	return nil
}

func StopProfileProbe() error {
	profileProbeMu.Lock()
	defer profileProbeMu.Unlock()
	mu.Lock()
	instance := profileProbe
	profileProbe = nil
	stopOlcrtc := profileProbeOlcrtc
	profileProbeOlcrtc = false
	mu.Unlock()
	if stopOlcrtc {
		olcrtc.Stop()
	}
	if instance != nil {
		if err := instance.Close(); err != nil {
			return fmt.Errorf("stop profile probe: %w", err)
		}
	}
	return nil
}

func newXrayInstance(assetDirectory string, configJSON string) (*core.Instance, error) {
	if err := setXrayAssetDirectory(assetDirectory); err != nil {
		return nil, err
	}
	config, err := core.LoadConfig("json", bytes.NewBufferString(configJSON))
	if err != nil {
		return nil, fmt.Errorf("load Xray config: %w", err)
	}
	instance, err := core.New(config)
	if err != nil {
		return nil, fmt.Errorf("create Xray instance: %w", err)
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		return nil, fmt.Errorf("start Xray instance: %w", err)
	}
	return instance, nil
}

type destinationDialer func(context.Context, xraynet.Destination) (net.Conn, error)

func runURLTest(link string, timeoutMillis int, dial destinationDialer) (int64, error) {
	return runURLTestWithInboundTag(link, timeoutMillis, latencyTestInboundTag, dial)
}

func runURLTestWithInboundTag(link string, timeoutMillis int, inboundTag string, dial destinationDialer) (int64, error) {
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	transport := &http.Transport{
		DisableKeepAlives:   true,
		ForceAttemptHTTP2:   true,
		TLSHandshakeTimeout: timeout,
		DialContext: func(ctx context.Context, network string, address string) (net.Conn, error) {
			destination, err := xraynet.ParseDestination(fmt.Sprintf("%s:%s", network, address))
			if err != nil {
				return nil, err
			}
			ctx = session.ContextWithInbound(ctx, &session.Inbound{Tag: inboundTag})
			return dial(ctx, destination)
		},
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport: transport,
		Timeout:   timeout,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	request, err := http.NewRequest(http.MethodHead, link, nil)
	if err != nil {
		return 0, err
	}
	startedAt := time.Now()
	response, err := client.Do(request)
	if err != nil {
		return 0, err
	}
	response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		return 0, fmt.Errorf("URL test returned HTTP %d", response.StatusCode)
	}
	return time.Since(startedAt).Milliseconds(), nil
}

func StartOlcrtc(
	provider string,
	transport string,
	roomID string,
	clientID string,
	keyHex string,
	authToken string,
	dnsServer string,
	vp8FPS int,
	vp8BatchSize int,
	keepaliveSeconds int,
	socksPort int,
) error {
	if olcrtc.IsRunning() {
		return errAlreadyRunning
	}

	olcrtc.SetProviders()
	olcrtc.SetDNS(dnsServer)
	olcrtc.SetWBToken(authToken)
	olcrtc.SetVP8Options(vp8FPS, vp8BatchSize)
	olcrtc.SetLivenessOptions(keepaliveSeconds*1000, 0, 0)
	if err := olcrtc.StartWithTransport(
		provider,
		transport,
		roomID,
		clientID,
		keyHex,
		socksPort,
		"",
		"",
	); err != nil {
		return fmt.Errorf("start olcRTC: %w", err)
	}
	return nil
}

func WaitOlcrtcReady(timeoutMillis int) error {
	if !olcrtc.IsRunning() {
		return errNotRunning
	}
	if err := olcrtc.WaitReady(timeoutMillis); err != nil {
		return fmt.Errorf("wait for olcRTC: %w", err)
	}
	return nil
}

func StopOlcrtc() {
	olcrtc.Stop()
}

func IsOlcrtcRunning() bool {
	return olcrtc.IsRunning()
}

func TrafficBytesUp() int64 {
	return 0
}

func TrafficBytesDown() int64 {
	return 0
}

func XrayVersion() string {
	return dependencyVersion("github.com/xtls/xray-core")
}

func OlcrtcVersion() string {
	if value := dependencyVersion("github.com/openlibrecommunity/olcrtc"); value != "unknown" {
		return value
	}
	return dependencyVersion("github.com/Oleglog/Olcrtc_manager")
}

func dependencyVersion(path string) string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "unknown"
	}
	for _, dep := range info.Deps {
		if dep.Path == path {
			if dep.Replace != nil {
				return dep.Replace.Version
			}
			return dep.Version
		}
	}
	return "unknown"
}

func IsFatalError(message string) bool {
	return strings.HasPrefix(message, "load Xray config:") ||
		strings.HasPrefix(message, "create Xray instance:") ||
		strings.Contains(message, "carrier auth failed") ||
		strings.Contains(message, "SOCKS5 authentication failed") ||
		strings.Contains(message, "message authentication failed")
}

func StopAll() error {
	olcrtc.Stop()
	return StopXray()
}

func protectSocket(_ string, _ string, conn syscall.RawConn) error {
	mu.Lock()
	current := protector
	mu.Unlock()
	if current == nil {
		return nil
	}

	var protected bool
	if err := conn.Control(func(fd uintptr) {
		protected = current.Protect(int(fd))
	}); err != nil {
		return err
	}
	if !protected {
		return errors.New("protect socket")
	}
	return nil
}

const latencyTestInboundTag = "latency-test"
