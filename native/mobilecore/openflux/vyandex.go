package openflux

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"golang.org/x/net/http2"
)

type VolgaConfig struct {
	MaxIdleConnsPerHost int
	MaxIdleConns        int
	IdleConnTimeout     time.Duration
	RelayTimeout        time.Duration

	WorkerCount int
	QueueSize   int

	BatchSize     int
	BatchTimeout  time.Duration
	BatchMaxBytes int

	MaxPayloadBytes int
	MinPayloadBytes int

	ReconnectMinDelay   time.Duration
	ReconnectMaxDelay   time.Duration
	ReconnectMultiplier float64

	WSHandshakeTimeout time.Duration
	WSReadTimeout      time.Duration
	KeepAliveInterval  time.Duration
}

func DefaultVolgaConfig() VolgaConfig {
	return VolgaConfig{
		MaxIdleConnsPerHost: 2000,
		MaxIdleConns:        4000,
		IdleConnTimeout:     90 * time.Second,
		RelayTimeout:        30 * time.Second,

		WorkerCount: 2000,
		QueueSize:   1000000,

		BatchSize:     20,
		BatchTimeout:  2 * time.Millisecond,
		BatchMaxBytes: 4 * 1024 * 1024,

		MaxPayloadBytes: 5_000_000,
		MinPayloadBytes: 200,

		ReconnectMinDelay:   500 * time.Millisecond,
		ReconnectMaxDelay:   30 * time.Second,
		ReconnectMultiplier: 1.5,

		WSHandshakeTimeout: 10 * time.Second,
		WSReadTimeout:      60 * time.Second,
		KeepAliveInterval:  10 * time.Second,
	}
}

const volgaUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:153.0) Gecko/20100101 Firefox/153.0"

var reClientConfig = regexp.MustCompile(`<script[^>]*id="client-config"[^>]*>(.*?)</script>`)

var (
	b64BufPool = sync.Pool{
		New: func() interface{} { return make([]byte, 0, 16*1024*1024) },
	}
	blobBufPool = sync.Pool{
		New: func() interface{} { return bytes.NewBuffer(make([]byte, 0, 64*1024)) },
	}
)

func base64Encode(data []byte) string {
	buf := b64BufPool.Get().([]byte)
	need := base64.StdEncoding.EncodedLen(len(data))
	if cap(buf) < need {
		buf = make([]byte, need)
	} else {
		buf = buf[:need]
	}
	base64.StdEncoding.Encode(buf, data)
	out := string(buf)
	b64BufPool.Put(buf[:0])
	return out
}

type VolgaStats struct {
	PacketsSent    atomic.Uint64
	PacketsRecv    atomic.Uint64
	BytesSent      atomic.Uint64
	BytesReceived  atomic.Uint64
	HTTPReqsSent   atomic.Uint64
	HTTPReqsFailed atomic.Uint64
	WSReconnects   atomic.Uint64
	QueueDrops     atomic.Uint64
	WorkerBusy     atomic.Int64
	BatchesSent    atomic.Uint64
	PacketsBatched atomic.Uint64
}

type volgaAuth struct {
	Session     *http.Client
	AccessToken string
	Token       string
	RequestPath string
	ResourceURL string
	DocID       string
	UserID      int
	UserIDStr   string
	Sign        string
	TS          string
	SessionID   string
	Cookies     []*http.Cookie
}

func authorize(docURL string) (*volgaAuth, error) {
	jar, _ := cookiejar.New(nil)
	session := &http.Client{
		Jar: jar,
		Transport: &http.Transport{
			MaxIdleConns:        100,
			MaxIdleConnsPerHost: 100,
			IdleConnTimeout:     90 * time.Second,
		},
		Timeout: 30 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	req, err := http.NewRequest("GET", docURL, nil)
	if err != nil {
		return nil, fmt.Errorf("create initial request: %w", err)
	}
	req.Header.Set("User-Agent", volgaUserAgent)

	resp, err := session.Do(req)
	if err != nil {
		return nil, fmt.Errorf("initial GET %s: %w", docURL, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("initial request returned HTTP %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read initial response: %w", err)
	}

	m := reClientConfig.FindSubmatch(body)
	if len(m) < 2 {
		return nil, fmt.Errorf("client-config not found in HTML")
	}

	var cc map[string]interface{}
	if err := json.Unmarshal(m[1], &cc); err != nil {
		return nil, fmt.Errorf("unmarshal client-config: %w", err)
	}

	var resourceURL string
	if res, ok := cc["resource"].(map[string]interface{}); ok {
		resourceURL, _ = res["url"].(string)
	}
	if resourceURL == "" {
		resourceURL = docURL
	}

	params, _ := cc["params"].(map[string]interface{})
	if params == nil {
		return nil, fmt.Errorf("params not found in client-config")
	}

	accessToken, _ := params["access_token"].(string)
	token, _ := params["token"].(string)
	requestPath, _ := params["request_path"].(string)
	docID, _ := params["id"].(string)

	var userID int
	switch v := params["userId"].(type) {
	case float64:
		userID = int(v)
	case string:
		userID, _ = strconv.Atoi(v)
	}

	sign, _ := params["sign"].(string)
	ts, _ := params["ts"].(string)

	if token == "" || docID == "" {
		return nil, fmt.Errorf("incomplete params in client-config")
	}

	u, _ := url.Parse(docURL)
	cookies := session.Jar.Cookies(u)

	return &volgaAuth{
		Session:     session,
		AccessToken: accessToken,
		Token:       token,
		RequestPath: requestPath,
		ResourceURL: resourceURL,
		DocID:       docID,
		UserID:      userID,
		UserIDStr:   strconv.Itoa(userID),
		Sign:        sign,
		TS:          ts,
		SessionID:   fmt.Sprintf("session-%d", time.Now().UnixNano()),
		Cookies:     cookies,
	}, nil
}

type YandexVolgaTransport struct {
	*BaseTransport
	docURL      string
	config      VolgaConfig
	stats       VolgaStats
	auth        *volgaAuth
	relay       *relayClient
	ws          *wsListener
	runningFlag atomic.Bool
	stopCh      chan struct{}
}

func NewYandexVolgaTransport(docURL string, cfg TransportConfig) *YandexVolgaTransport {
	vcfg := DefaultVolgaConfig()
	t := &YandexVolgaTransport{
		BaseTransport: NewBaseTransport(cfg),
		docURL:        docURL,
		config:        vcfg,
		stopCh:        make(chan struct{}),
	}
	return t
}

func (t *YandexVolgaTransport) Start() error {
	if !t.runningFlag.CompareAndSwap(false, true) {
		return nil
	}
	_ = t.BaseTransport.Start()

	auth, err := authorize(t.docURL)
	if err != nil {
		t.runningFlag.Store(false)
		return fmt.Errorf("volga authorize: %w", err)
	}
	t.auth = auth

	relay, err := newRelayClient(auth, t.config, &t.stats)
	if err != nil {
		t.runningFlag.Store(false)
		return fmt.Errorf("volga newRelayClient: %w", err)
	}
	t.relay = relay
	relay.Start()

	ws := newWSListener(auth, relay, t.config, &t.stats, func(data []byte) {
		t.AddRecvBytes(len(data))
		t.CallReceive(data)
	})
	t.ws = ws

	if err := ws.Connect(); err != nil {
		relay.Stop()
		t.runningFlag.Store(false)
		return fmt.Errorf("volga ws connect: %w", err)
	}
	ws.Start()

	t.SetConnected(true)
	return nil
}

func (t *YandexVolgaTransport) Stop() error {
	if !t.runningFlag.CompareAndSwap(true, false) {
		return nil
	}
	t.SetConnected(false)
	_ = t.BaseTransport.Stop()

	if t.ws != nil {
		t.ws.Stop()
	}
	if t.relay != nil {
		t.relay.Stop()
	}
	return nil
}

func (t *YandexVolgaTransport) Send(data []byte) error {
	if !t.runningFlag.Load() || t.relay == nil {
		return fmt.Errorf("transport not running")
	}
	t.AddSentBytes(len(data))
	return t.relay.Enqueue(data)
}

func (t *YandexVolgaTransport) IsConnected() bool {
	return t.runningFlag.Load() && t.ws != nil && t.ws.IsConnected()
}

type relayClient struct {
	auth        *volgaAuth
	config      VolgaConfig
	stats       *VolgaStats
	client      *http.Client
	relayURL    string
	batchQueue  chan []byte
	frontier    atomic.Value
	seq         atomic.Uint64
	localID     atomic.Uint64
	ctx         context.Context
	cancel      context.CancelFunc
	wg          sync.WaitGroup
}

func newRelayClient(auth *volgaAuth, cfg VolgaConfig, stats *VolgaStats) (*relayClient, error) {
	jar, _ := cookiejar.New(nil)
	if len(auth.Cookies) > 0 {
		u, _ := url.Parse(auth.ResourceURL)
		jar.SetCookies(u, auth.Cookies)
	}

	transport := &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   10 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		MaxIdleConns:        cfg.MaxIdleConns,
		MaxIdleConnsPerHost: cfg.MaxIdleConnsPerHost,
		IdleConnTimeout:     cfg.IdleConnTimeout,
		TLSClientConfig:     &tls.Config{InsecureSkipVerify: false},
	}
	_ = http2.ConfigureTransport(transport)

	client := &http.Client{
		Transport: transport,
		Timeout:   cfg.RelayTimeout,
		Jar:       jar,
	}

	docURLParsed, _ := url.Parse(auth.ResourceURL)
	relayURL := fmt.Sprintf("https://%s/api/office-volga/relay?token=%s", docURLParsed.Host, auth.Token)

	ctx, cancel := context.WithCancel(context.Background())
	r := &relayClient{
		auth:       auth,
		config:     cfg,
		stats:      stats,
		client:     client,
		relayURL:   relayURL,
		batchQueue: make(chan []byte, cfg.QueueSize),
		ctx:        ctx,
		cancel:     cancel,
	}
	r.frontier.Store([]interface{}{"1-0.0"})
	return r, nil
}

func (r *relayClient) Start() {
	r.wg.Add(r.config.WorkerCount)
	for i := 0; i < r.config.WorkerCount; i++ {
		go r.worker(i)
	}
}

func (r *relayClient) Stop() {
	r.cancel()
	r.wg.Wait()
}

func (r *relayClient) SetFrontier(opID string) {
	r.frontier.Store([]interface{}{opID})
}

func (r *relayClient) getFrontier() []interface{} {
	return r.frontier.Load().([]interface{})
}

func (r *relayClient) Enqueue(data []byte) error {
	select {
	case r.batchQueue <- append([]byte(nil), data...):
		return nil
	default:
		r.stats.QueueDrops.Add(1)
		return fmt.Errorf("queue full")
	}
}

func (r *relayClient) worker(id int) {
	defer r.wg.Done()

	batch := make([][]byte, 0, r.config.BatchSize)
	totalBytes := 0
	timer := time.NewTimer(r.config.BatchTimeout)
	if !timer.Stop() {
		<-timer.C
	}
	defer timer.Stop()

	flush := func() {
		if len(batch) == 0 {
			return
		}
		_ = r.sendBatch(batch)
		batch = batch[:0]
		totalBytes = 0
	}

	for {
		select {
		case <-r.ctx.Done():
			flush()
			return
		case pkt, ok := <-r.batchQueue:
			if !ok {
				flush()
				return
			}
			batch = append(batch, pkt)
			totalBytes += len(pkt)
			if len(batch) >= r.config.BatchSize || totalBytes >= r.config.BatchMaxBytes {
				flush()
			} else if len(batch) == 1 {
				timer.Reset(r.config.BatchTimeout)
			}
		case <-timer.C:
			flush()
		}
	}
}

func (r *relayClient) sendBatch(batch [][]byte) error {
	blob := blobBufPool.Get().(*bytes.Buffer)
	blob.Reset()

	var lenBuf [2]byte
	for _, p := range batch {
		binary.BigEndian.PutUint16(lenBuf[:], uint16(len(p)))
		blob.Write(lenBuf[:])
		blob.Write(p)
	}

	encoded := base64Encode(blob.Bytes())
	blobBufPool.Put(blob)

	frontier := r.getFrontier()
	opID := fmt.Sprintf("1-%d.%d", r.auth.UserID, r.seq.Add(1))
	relayOpID := fmt.Sprintf("1-%d.%d", r.auth.UserID, r.seq.Add(1))

	bundle := []interface{}{
		map[string]interface{}{
			"id":         opID,
			"frontier":   frontier,
			"undoable":   true,
			"actionName": "textInsert",
			"ops":        []interface{}{[]interface{}{"it", "vyd:t/00000000000008", 0, "A"}},
			"sideEffect": false,
			"localId":    r.localID.Add(1),
		},
		map[string]interface{}{
			"id":         relayOpID,
			"frontier":   []interface{}{opID},
			"undoable":   false,
			"actionName": "setCaret",
			"ops": []interface{}{
				[]interface{}{"s", "00000000000008", 0, 0},
			},
			"sideEffect": false,
			"localId":    r.localID.Add(1),
		},
		encoded,
	}

	payload := map[string]interface{}{
		"bundle": bundle,
	}

	bodyJSON, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(r.ctx, "POST", r.relayURL, bytes.NewReader(bodyJSON))
	if err != nil {
		return err
	}

	req.Header.Set("Content-Type", "application/json;charset=UTF-8")
	req.Header.Set("User-Agent", volgaUserAgent)
	req.Header.Set("X-Session-Id", r.auth.SessionID)

	resp, err := r.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	return nil
}

type wsListener struct {
	auth        *volgaAuth
	relay       *relayClient
	config      VolgaConfig
	stats       *VolgaStats
	onPacket    func([]byte)
	wsURL       string
	conn        *websocket.Conn
	connMu      sync.Mutex
	connected   atomic.Bool
	ctx         context.Context
	cancel      context.CancelFunc
	wg          sync.WaitGroup
}

func newWSListener(auth *volgaAuth, relay *relayClient, cfg VolgaConfig, stats *VolgaStats, onPacket func([]byte)) *wsListener {
	docURLParsed, _ := url.Parse(auth.ResourceURL)
	wsURL := fmt.Sprintf("wss://%s/api/office-volga/ws?token=%s", docURLParsed.Host, auth.Token)

	ctx, cancel := context.WithCancel(context.Background())
	return &wsListener{
		auth:     auth,
		relay:    relay,
		config:   cfg,
		stats:    stats,
		onPacket: onPacket,
		wsURL:    wsURL,
		ctx:      ctx,
		cancel:   cancel,
	}
}

func (w *wsListener) Connect() error {
	dialer := websocket.Dialer{
		HandshakeTimeout: w.config.WSHandshakeTimeout,
	}

	headers := http.Header{}
	headers.Set("User-Agent", volgaUserAgent)

	conn, _, err := dialer.Dial(w.wsURL, headers)
	if err != nil {
		return err
	}

	w.connMu.Lock()
	w.conn = conn
	w.connMu.Unlock()
	w.connected.Store(true)
	return nil
}

func (w *wsListener) Start() {
	w.wg.Add(1)
	go w.readLoop()
}

func (w *wsListener) Stop() {
	w.cancel()
	w.connMu.Lock()
	if w.conn != nil {
		_ = w.conn.Close()
	}
	w.connMu.Unlock()
	w.wg.Wait()
}

func (w *wsListener) IsConnected() bool {
	return w.connected.Load()
}

func (w *wsListener) readLoop() {
	defer w.wg.Done()
	for {
		select {
		case <-w.ctx.Done():
			return
		default:
		}

		w.connMu.Lock()
		conn := w.conn
		w.connMu.Unlock()

		if conn == nil {
			time.Sleep(500 * time.Millisecond)
			continue
		}

		_, msg, err := conn.ReadMessage()
		if err != nil {
			w.connected.Store(false)
			select {
			case <-w.ctx.Done():
				return
			default:
				time.Sleep(1 * time.Second)
				_ = w.Connect()
				continue
			}
		}

		w.handleMessage(msg)
	}
}

func (w *wsListener) handleMessage(raw []byte) {
	var envelope struct {
		Operation string `json:"operation"`
		Message   string `json:"message"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return
	}
	if envelope.Operation != "SESSION" && envelope.Operation != "WORKER" || envelope.Message == "" {
		return
	}

	var inner struct {
		T       string          `json:"t"`
		UserID  int             `json:"userId"`
		Bundle  json.RawMessage `json:"bundle"`
		Message json.RawMessage `json:"message"`
	}
	if err := json.Unmarshal([]byte(envelope.Message), &inner); err != nil || inner.UserID == w.auth.UserID {
		return
	}

	switch inner.T {
	case "relay":
		var relay struct {
			Bundle []json.RawMessage `json:"bundle"`
		}
		if err := json.Unmarshal(inner.Message, &relay); err == nil {
			for _, item := range relay.Bundle {
				w.handleBundleItem(item)
			}
		}
	case "exchange":
		var asArray []json.RawMessage
		if err := json.Unmarshal(inner.Bundle, &asArray); err == nil {
			for _, item := range asArray {
				w.handleBundleItem(item)
			}
		}
	}
}

func (w *wsListener) handleBundleItem(raw json.RawMessage) {
	var asObj struct {
		ID     string `json:"id"`
		Action string `json:"actionName"`
	}
	if err := json.Unmarshal(raw, &asObj); err == nil && asObj.Action != "" {
		if asObj.ID != "" {
			w.relay.SetFrontier(asObj.ID)
		}
		return
	}

	var asStr string
	if err := json.Unmarshal(raw, &asStr); err == nil && asStr != "" {
		decoded, err := base64.StdEncoding.DecodeString(asStr)
		if err == nil {
			packets := decodeBatch(decoded)
			for _, p := range packets {
				if w.onPacket != nil {
					w.onPacket(p)
				}
			}
		}
	}
}

func decodeBatch(data []byte) [][]byte {
	var packets [][]byte
	r := bytes.NewReader(data)
	var lenBuf [2]byte
	for r.Len() >= 2 {
		if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
			break
		}
		pktLen := int(binary.BigEndian.Uint16(lenBuf[:]))
		if pktLen <= 0 || pktLen > r.Len() {
			break
		}
		pkt := make([]byte, pktLen)
		if _, err := io.ReadFull(r, pkt); err != nil {
			break
		}
		packets = append(packets, pkt)
	}
	return packets
}
