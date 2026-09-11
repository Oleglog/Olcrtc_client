package openflux

import (
	"bytes"
	"crypto/tls"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"golang.org/x/net/http2"
)

// Yandex Volga Transport implementation for embedded OpenFlux.

type volgaConfig struct {
	MaxIdleConnsPerHost int
	MaxIdleConns        int
	IdleConnTimeout     time.Duration
	RelayTimeout        time.Duration
	WorkerCount         int
	QueueSize           int
	BatchSize           int
	BatchTimeout        time.Duration
	BatchMaxBytes       int
	MaxPayloadBytes     int
	MinPayloadBytes     int
	WSHandshakeTimeout  time.Duration
	WSReadTimeout       time.Duration
	KeepAliveInterval   time.Duration
}

func defaultVolgaConfig() volgaConfig {
	return volgaConfig{
		MaxIdleConnsPerHost: 2000,
		MaxIdleConns:        4000,
		IdleConnTimeout:     90 * time.Second,
		RelayTimeout:        30 * time.Second,
		WorkerCount:         2000,
		QueueSize:           1000000,
		BatchSize:           20,
		BatchTimeout:        2 * time.Millisecond,
		BatchMaxBytes:       4 * 1024 * 1024,
		MaxPayloadBytes:     5_000_000,
		MinPayloadBytes:     200,
		WSHandshakeTimeout:  10 * time.Second,
		WSReadTimeout:       60 * time.Second,
		KeepAliveInterval:   10 * time.Second,
	}
}

type yandexVolgaTransport struct {
	docURL       string
	clientDocURL string
	wsURL        string
	token        string
	docID        string
	sessionID    string
	config       config
	volgaConfig  volgaConfig
	wsConn       *websocket.Conn
	wsMu         sync.Mutex
	httpClient   *http.Client
	queue        chan []byte
	recvHandler  func(data []byte)
	running      atomic.Bool
	connected    atomic.Bool
	stopCh       chan struct{}
}

func newYandexVolgaTransport(docURL string, cfg config) *yandexVolgaTransport {
	vc := defaultVolgaConfig()
	transport := &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   10 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		MaxIdleConns:        vc.MaxIdleConns,
		MaxIdleConnsPerHost: vc.MaxIdleConnsPerHost,
		IdleConnTimeout:     vc.IdleConnTimeout,
		TLSClientConfig:     &tls.Config{InsecureSkipVerify: false},
	}
	_ = http2.ConfigureTransport(transport)

	client := &http.Client{
		Transport: transport,
		Timeout:   vc.RelayTimeout,
	}

	return &yandexVolgaTransport{
		docURL:       docURL,
		clientDocURL: docURL,
		config:       cfg,
		volgaConfig:  vc,
		httpClient:   client,
		queue:        make(chan []byte, vc.QueueSize),
		stopCh:       make(chan struct{}),
	}
}

func (t *yandexVolgaTransport) Start() error {
	if !t.running.CompareAndSwap(false, true) {
		return nil
	}

	if err := t.resolveDocInfo(); err != nil {
		t.running.Store(false)
		return fmt.Errorf("resolve doc info: %w", err)
	}

	if err := t.connectWebSocket(); err != nil {
		t.running.Store(false)
		return fmt.Errorf("connect websocket: %w", err)
	}

	go t.batchSenderLoop()
	go t.wsReadLoop()
	return nil
}

func (t *yandexVolgaTransport) Stop() error {
	if !t.running.CompareAndSwap(true, false) {
		return nil
	}
	t.connected.Store(false)
	close(t.stopCh)

	t.wsMu.Lock()
	if t.wsConn != nil {
		_ = t.wsConn.Close()
		t.wsConn = nil
	}
	t.wsMu.Unlock()
	return nil
}

func (t *yandexVolgaTransport) Send(data []byte) error {
	if !t.running.Load() {
		return fmt.Errorf("transport stopped")
	}
	select {
	case t.queue <- append([]byte(nil), data...):
		return nil
	default:
		return fmt.Errorf("queue full")
	}
}

func (t *yandexVolgaTransport) Receive(handler func(data []byte)) {
	t.recvHandler = handler
}

func (t *yandexVolgaTransport) IsConnected() bool {
	return t.connected.Load()
}

func (t *yandexVolgaTransport) resolveDocInfo() error {
	req, err := http.NewRequest("GET", t.clientDocURL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
	resp, err := t.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 1024*1024))
	if err != nil {
		return err
	}

	content := string(body)
	t.token = extractBetween(content, `"token":"`, `"`)
	t.docID = extractBetween(content, `"id":"`, `"`)
	t.sessionID = fmt.Sprintf("session-%d", time.Now().UnixNano())

	u, err := url.Parse(t.clientDocURL)
	if err != nil {
		return err
	}
	t.wsURL = fmt.Sprintf("wss://%s/api/office-volga/ws?token=%s", u.Host, t.token)
	return nil
}

func (t *yandexVolgaTransport) connectWebSocket() error {
	dialer := websocket.Dialer{
		HandshakeTimeout: t.volgaConfig.WSHandshakeTimeout,
	}
	conn, _, err := dialer.Dial(t.wsURL, nil)
	if err != nil {
		return err
	}

	t.wsMu.Lock()
	t.wsConn = conn
	t.wsMu.Unlock()

	t.connected.Store(true)
	return nil
}

func (t *yandexVolgaTransport) wsReadLoop() {
	defer t.connected.Store(false)
	for t.running.Load() {
		t.wsMu.Lock()
		conn := t.wsConn
		t.wsMu.Unlock()
		if conn == nil {
			return
		}

		_, message, err := conn.ReadMessage()
		if err != nil {
			if t.running.Load() {
				time.Sleep(500 * time.Millisecond)
				_ = t.connectWebSocket()
			}
			continue
		}

		if t.recvHandler != nil && len(message) > 0 {
			if bytes.HasPrefix(message, []byte("data:")) {
				rawPayload := bytes.TrimPrefix(message, []byte("data:"))
				decoded, err := base64.StdEncoding.DecodeString(string(rawPayload))
				if err == nil {
					t.recvHandler(decoded)
				}
			}
		}
	}
}

func (t *yandexVolgaTransport) batchSenderLoop() {
	for t.running.Load() {
		select {
		case <-t.stopCh:
			return
		case item := <-t.queue:
			encoded := "data:" + base64.StdEncoding.EncodeToString(item)
			t.wsMu.Lock()
			conn := t.wsConn
			if conn != nil {
				_ = conn.WriteMessage(websocket.TextMessage, []byte(encoded))
			}
			t.wsMu.Unlock()
		}
	}
}

// Classic Yandex Docs Transport

type yandexDocsTransport struct {
	docURL      string
	config      config
	running     atomic.Bool
	connected   atomic.Bool
	recvHandler func(data []byte)
}

func newYandexDocsTransport(docURL string, cfg config) *yandexDocsTransport {
	return &yandexDocsTransport{
		docURL: docURL,
		config: cfg,
	}
}

func (t *yandexDocsTransport) Start() error {
	t.running.Store(true)
	t.connected.Store(true)
	return nil
}

func (t *yandexDocsTransport) Stop() error {
	t.running.Store(false)
	t.connected.Store(false)
	return nil
}

func (t *yandexDocsTransport) Send(data []byte) error {
	return nil
}

func (t *yandexDocsTransport) Receive(handler func(data []byte)) {
	t.recvHandler = handler
}

func (t *yandexDocsTransport) IsConnected() bool {
	return t.connected.Load()
}

func extractBetween(value, start, end string) string {
	sIdx := strings.Index(value, start)
	if sIdx == -1 {
		return ""
	}
	sIdx += len(start)
	eIdx := strings.Index(value[sIdx:], end)
	if eIdx == -1 {
		return ""
	}
	return value[sIdx : sIdx+eIdx]
}
