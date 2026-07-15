package mobilecore

import (
	"bytes"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"

	olcrtc "github.com/openlibrecommunity/olcrtc/mobile"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
	"github.com/xtls/xray-core/transport/internet"
)

var (
	errAlreadyRunning = errors.New("mobilecore already running")
	errNotRunning     = errors.New("mobilecore is not running")
	mu                sync.Mutex
	protector         SocketProtector
	xrayInstance      *core.Instance
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
	if err := setXrayAssetDirectory(assetDirectory); err != nil {
		return err
	}

	config, err := core.LoadConfig("json", bytes.NewBufferString(configJSON))
	if err != nil {
		return fmt.Errorf("load Xray config: %w", err)
	}

	instance, err := core.New(config)
	if err != nil {
		return fmt.Errorf("create Xray instance: %w", err)
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		return fmt.Errorf("start Xray instance: %w", err)
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
