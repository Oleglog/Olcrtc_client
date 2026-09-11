package openflux

import (
	"encoding/binary"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/pierrec/lz4/v4"
)

type transport interface {
	Start() error
	Stop() error
	Send(data []byte) error
	Receive(handler func(data []byte))
	IsConnected() bool
}

type config struct {
	MaxPacketSize int
	MaxQueueSize  int
}

func defaultConfig() config {
	return config{
		MaxPacketSize: 1500,
		MaxQueueSize:  10000,
	}
}

func detectTransport(docURL string) string {
	httpClient := &http.Client{Timeout: 10 * time.Second}
	req, err := http.NewRequest("GET", docURL, nil)
	if err != nil {
		return "yandex"
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
	resp, err := httpClient.Do(req)
	if err != nil {
		return "yandex"
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 1024*1024))
	if err != nil {
		return "yandex"
	}

	content := string(body)
	if strings.Contains(content, `"officeType":"volga"`) || (strings.Contains(content, "volga") && !strings.Contains(content, "balancer_url")) {
		return "vyandex"
	}
	return "yandex"
}

const (
	flagUncompressed byte = 0x00
	flagCompressed   byte = 0x01
	minCompressSize       = 200
)

type compressedTransport struct {
	inner transport
}

func newCompressedTransport(inner transport) *compressedTransport {
	return &compressedTransport{inner: inner}
}

func (c *compressedTransport) Start() error { return c.inner.Start() }
func (c *compressedTransport) Stop() error  { return c.inner.Stop() }
func (c *compressedTransport) IsConnected() bool { return c.inner.IsConnected() }

func (c *compressedTransport) Send(data []byte) error {
	if len(data) < minCompressSize {
		pkt := make([]byte, 1+len(data))
		pkt[0] = flagUncompressed
		copy(pkt[1:], data)
		return c.inner.Send(pkt)
	}

	compressed := make([]byte, lz4.CompressBlockBound(len(data)))
	n, err := lz4.CompressBlock(data, compressed, nil)
	if err != nil || n >= len(data) {
		pkt := make([]byte, 1+len(data))
		pkt[0] = flagUncompressed
		copy(pkt[1:], data)
		return c.inner.Send(pkt)
	}

	pkt := make([]byte, 1+4+n)
	pkt[0] = flagCompressed
	binary.BigEndian.PutUint32(pkt[1:5], uint32(len(data)))
	copy(pkt[5:], compressed[:n])
	return c.inner.Send(pkt)
}

func (c *compressedTransport) Receive(handler func(data []byte)) {
	c.inner.Receive(func(data []byte) {
		if len(data) == 0 {
			return
		}
		flag := data[0]
		payload := data[1:]

		switch flag {
		case flagUncompressed:
			handler(payload)
		case flagCompressed:
			if len(payload) < 4 {
				return
			}
			origSize := binary.BigEndian.Uint32(payload[:4])
			compressedData := payload[4:]
			decompressed := make([]byte, origSize)
			n, err := lz4.DecompressSafe(compressedData, decompressed)
			if err != nil || uint32(n) != origSize {
				return
			}
			handler(decompressed)
		}
	})
}
