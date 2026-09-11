package openflux

import (
	"encoding/base64"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

type YandexDocsInfo struct {
	CookieStr   string
	Token       string
	DocID       string
	CallbackURL string
	UserID      string
	Origin      string
	Host        string
	WsURL       string
	Permissions map[string]interface{}
	OpenCmd     map[string]interface{}
}

type DocSession struct {
	Info       YandexDocsInfo
	Conn       *websocket.Conn
	WriteQueue chan []byte
	UserID     string
	writeMu    sync.Mutex
}

func (s *DocSession) safeWrite(messageType int, data []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return s.Conn.WriteMessage(messageType, data)
}

type YandexDocsTransport struct {
	*BaseTransport

	url     string
	session *DocSession

	userCounter atomic.Int32
	baseUserID  string
}

func NewYandexDocsTransport(url string, config TransportConfig) *YandexDocsTransport {
	t := &YandexDocsTransport{
		BaseTransport: NewBaseTransport(config),
		url:           url,
	}
	t.baseUserID = randUserID()
	return t
}

func (t *YandexDocsTransport) Start() error {
	if err := t.BaseTransport.Start(); err != nil {
		return err
	}

	t.baseUserID = randUserID()
	go t.keepAliveLoop()
	t.connectToDoc(0)

	return nil
}

func (t *YandexDocsTransport) Stop() error {
	_ = t.BaseTransport.Stop()

	t.Mu.Lock()
	if t.session != nil && t.session.Conn != nil {
		_ = t.session.Conn.Close()
		t.session = nil
	}
	t.Mu.Unlock()

	return nil
}

func (t *YandexDocsTransport) Send(data []byte) error {
	if !t.IsConnected() {
		return fmt.Errorf("not connected")
	}

	t.Mu.RLock()
	s := t.session
	t.Mu.RUnlock()

	if s == nil || s.Conn == nil {
		return fmt.Errorf("no active session")
	}

	encoded := base64.StdEncoding.EncodeToString(data)
	msg := fmt.Sprintf(`42["message",{"type":"chat","data":"%s"}]`, encoded)

	err := s.safeWrite(websocket.TextMessage, []byte(msg))
	if err != nil {
		return err
	}

	t.AddSentBytes(len(data))
	return nil
}

func (t *YandexDocsTransport) connectToDoc(attempt int) {
	if !t.IsRunning() {
		return
	}

	for {
		if !t.IsRunning() {
			return
		}

		info, err := t.fetchDocInfo()
		if err != nil {
			t.handleReconnect(attempt, err)
			return
		}

		session, err := t.startWsSession(info)
		if err != nil {
			t.handleReconnect(attempt, err)
			return
		}

		t.Mu.Lock()
		t.session = session
		t.Mu.Unlock()

		t.SetConnected(true)
		t.ResetReconnectAttempts()

		t.readLoop(session)

		t.SetConnected(false)
		t.AddReconnect()

		if !t.IsRunning() {
			return
		}
	}
}

func (t *YandexDocsTransport) handleReconnect(attempt int, err error) {
	if !t.IsRunning() {
		return
	}

	delay := t.GetReconnectDelay()
	time.Sleep(delay)
	go t.connectToDoc(attempt + 1)
}

func (t *YandexDocsTransport) fetchDocInfo() (*YandexDocsInfo, error) {
	client := &http.Client{
		Timeout: 10 * time.Second,
	}

	req, err := http.NewRequest("GET", t.url, nil)
	if err != nil {
		return nil, err
	}

	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	html := string(body)
	info := &YandexDocsInfo{}

	tokenRe := regexp.MustCompile(`"token":"([^"]+)"`)
	if m := tokenRe.FindStringSubmatch(html); len(m) > 1 {
		info.Token = m[1]
	}

	idRe := regexp.MustCompile(`"id":"([^"]+)"`)
	if m := idRe.FindStringSubmatch(html); len(m) > 1 {
		info.DocID = m[1]
	}

	balancerRe := regexp.MustCompile(`"balancer_url":"([^"]+)"`)
	if m := balancerRe.FindStringSubmatch(html); len(m) > 1 {
		info.WsURL = m[1]
	}

	if info.DocID == "" || info.WsURL == "" {
		return nil, fmt.Errorf("failed to parse Yandex doc info")
	}

	return info, nil
}

func (t *YandexDocsTransport) startWsSession(info *YandexDocsInfo) (*DocSession, error) {
	wsURL := fmt.Sprintf("%s/socket.io/1/?t=%d", info.WsURL, time.Now().UnixNano())
	resp, err := http.Get(wsURL)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	parts := strings.Split(string(body), ":")
	if len(parts) < 1 {
		return nil, fmt.Errorf("invalid socket.io handshake")
	}
	sid := parts[0]

	realWsURL := fmt.Sprintf("%s/socket.io/1/websocket/%s", strings.Replace(info.WsURL, "https://", "wss://", 1), sid)

	dialer := websocket.Dialer{
		HandshakeTimeout: 10 * time.Second,
	}

	conn, _, err := dialer.Dial(realWsURL, nil)
	if err != nil {
		return nil, err
	}

	session := &DocSession{
		Info:       *info,
		Conn:       conn,
		WriteQueue: make(chan []byte, 1000),
		UserID:     t.baseUserID,
	}

	return session, nil
}

func (t *YandexDocsTransport) readLoop(session *DocSession) {
	for t.IsRunning() {
		_, message, err := session.Conn.ReadMessage()
		if err != nil {
			return
		}

		msg := string(message)
		if strings.HasPrefix(msg, `42["message",`) {
			idx := strings.Index(msg, `"data":"`)
			if idx != -1 {
				idx += len(`"data":"`)
				end := strings.Index(msg[idx:], `"`)
				if end != -1 {
					rawB64 := msg[idx : idx+end]
					decoded, err := base64.StdEncoding.DecodeString(rawB64)
					if err == nil {
						t.AddRecvBytes(len(decoded))
						t.CallReceive(decoded)
					}
				}
			}
		}
	}
}

func (t *YandexDocsTransport) keepAliveLoop() {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	for t.IsRunning() {
		<-ticker.C
		t.Mu.RLock()
		s := t.session
		t.Mu.RUnlock()

		if s != nil && s.Conn != nil && t.IsConnected() {
			_ = s.safeWrite(websocket.TextMessage, []byte("2::"))
		}
	}
}

func randUserID() string {
	return fmt.Sprintf("user-%d", rand.Intn(1000000))
}
