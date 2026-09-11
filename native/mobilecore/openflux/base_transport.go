package openflux

import (
	"sync"
	"sync/atomic"
	"time"
)

type TransportConfig struct {
	MaxReconnectAttempts int
	ReconnectDelay       time.Duration
	ReconnectMultiplier  float64
	MaxQueueSize         int
	KeepAliveInterval    time.Duration
}

type Transport interface {
	Start() error
	Stop() error
	Send(data []byte) error
	Receive(callback func([]byte))
	IsConnected() bool
	Stats() TransportStats
}

type TransportStats struct {
	BytesSent     uint64
	BytesReceived uint64
	PacketsSent   uint64
	PacketsRecv   uint64
	Reconnects    uint64
	Connected     bool
	Uptime        time.Duration
}

func DefaultTransportConfig() TransportConfig {
	return TransportConfig{
		MaxReconnectAttempts: 999999,
		ReconnectDelay:       0,
		ReconnectMultiplier:  1.1,
		MaxQueueSize:         1024,
		KeepAliveInterval:    10 * time.Second,
	}
}

type BaseTransport struct {
	config    TransportConfig
	running   atomic.Int32
	connected atomic.Int32
	stats     TransportStats
	startTime time.Time

	receiveCallback func([]byte)
	Mu              sync.RWMutex

	reconnectAttempts atomic.Int32
}

func NewBaseTransport(config TransportConfig) *BaseTransport {
	return &BaseTransport{
		config:    config,
		startTime: time.Now(),
	}
}

func (b *BaseTransport) Start() error {
	b.running.Store(1)
	b.startTime = time.Now()
	return nil
}

func (b *BaseTransport) Stop() error {
	b.running.Store(0)
	b.connected.Store(0)
	return nil
}

func (b *BaseTransport) IsRunning() bool {
	return b.running.Load() == 1
}

func (b *BaseTransport) IsConnected() bool {
	return b.connected.Load() == 1
}

func (b *BaseTransport) SetConnected(connected bool) {
	if connected {
		b.connected.Store(1)
	} else {
		b.connected.Store(0)
	}
}

func (b *BaseTransport) Receive(callback func([]byte)) {
	b.Mu.Lock()
	defer b.Mu.Unlock()
	b.receiveCallback = callback
}

func (b *BaseTransport) CallReceive(data []byte) {
	b.Mu.RLock()
	cb := b.receiveCallback
	b.Mu.RUnlock()

	if cb != nil {
		cb(data)
	}
}

func (b *BaseTransport) Stats() TransportStats {
	b.Mu.RLock()
	defer b.Mu.RUnlock()

	stats := b.stats
	stats.Connected = b.IsConnected()
	if b.IsRunning() {
		stats.Uptime = time.Since(b.startTime)
	}
	return stats
}

func (b *BaseTransport) AddSentBytes(n int) {
	b.Mu.Lock()
	b.stats.BytesSent += uint64(n)
	b.stats.PacketsSent++
	b.Mu.Unlock()
}

func (b *BaseTransport) AddRecvBytes(n int) {
	b.Mu.Lock()
	b.stats.BytesReceived += uint64(n)
	b.stats.PacketsRecv++
	b.Mu.Unlock()
}

func (b *BaseTransport) AddReconnect() {
	b.Mu.Lock()
	b.stats.Reconnects++
	b.Mu.Unlock()
}

func (b *BaseTransport) GetReconnectDelay() time.Duration {
	attempts := b.reconnectAttempts.Add(1)
	if attempts == 1 {
		return b.config.ReconnectDelay
	}

	delay := float64(b.config.ReconnectDelay)
	for i := int32(1); i < attempts; i++ {
		delay *= b.config.ReconnectMultiplier
		if delay > float64(30*time.Second) {
			delay = float64(30 * time.Second)
			break
		}
	}
	return time.Duration(delay)
}

func (b *BaseTransport) ResetReconnectAttempts() {
	b.reconnectAttempts.Store(0)
}
