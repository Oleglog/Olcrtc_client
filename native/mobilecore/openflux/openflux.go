package openflux

import (
	"fmt"
	"net"
	"sync"
	"time"
)

// SOCKS5 and tunnel bridge for OpenFlux embedded in mobilecore.

var (
	fluxMu     sync.Mutex
	fluxServer *socksServer
	fluxTun    *tcpTunnel
	fluxTrans  *compressedTransport
)

func Start(docURL string, transportType string, socksPort int) error {
	fluxMu.Lock()
	defer fluxMu.Unlock()

	if fluxServer != nil {
		return fmt.Errorf("openflux is already running")
	}

	detected := transportType
	if detected == "" || detected == "auto" {
		detected = detectTransport(docURL)
	}

	cfg := defaultConfig()
	var rawTrans transport
	if detected == "vyandex" {
		rawTrans = newYandexVolgaTransport(docURL, cfg)
	} else {
		rawTrans = newYandexDocsTransport(docURL, cfg)
	}

	compressed := newCompressedTransport(rawTrans)
	if err := compressed.Start(); err != nil {
		return fmt.Errorf("start transport: %w", err)
	}

	tun := newTCPTunnel(compressed)
	server := newSOCKSServer(fmt.Sprintf("127.0.0.1:%d", socksPort), tun)
	if err := server.Start(); err != nil {
		_ = compressed.Stop()
		return fmt.Errorf("start SOCKS5: %w", err)
	}

	fluxServer = server
	fluxTun = tun
	fluxTrans = compressed
	return nil
}

func Stop() {
	fluxMu.Lock()
	defer fluxMu.Unlock()

	if fluxServer != nil {
		_ = fluxServer.Stop()
		fluxServer = nil
	}
	if fluxTrans != nil {
		_ = fluxTrans.Stop()
		fluxTrans = nil
	}
	fluxTun = nil
}

func IsRunning() bool {
	fluxMu.Lock()
	defer fluxMu.Unlock()
	return fluxServer != nil
}

func WaitReady(timeoutMillis int) error {
	deadline := time.Now().Add(time.Duration(timeoutMillis) * time.Millisecond)
	for {
		fluxMu.Lock()
		running := fluxServer != nil
		var port string
		if running && fluxServer.listener != nil {
			port = fluxServer.listener.Addr().String()
		}
		fluxMu.Unlock()

		if !running {
			return fmt.Errorf("openflux not running")
		}
		if port != "" {
			conn, err := net.DialTimeout("tcp", port, 100*time.Millisecond)
			if err == nil {
				_ = conn.Close()
				return nil
			}
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("timeout waiting for OpenFlux SOCKS5")
		}
		time.Sleep(50 * time.Millisecond)
	}
}
