package openflux

import (
	"fmt"
	"io"
	"net"
	"sync"
)

type dialer interface {
	DialTCP(address string) (net.Conn, error)
}

type socksServer struct {
	listenAddr string
	dialer     dialer
	mu         sync.Mutex
	listener   net.Listener
	stopped    bool
}

func newSOCKSServer(addr string, d dialer) *socksServer {
	return &socksServer{listenAddr: addr, dialer: d}
}

func (s *socksServer) Start() error {
	listener, err := net.Listen("tcp", s.listenAddr)
	if err != nil {
		return err
	}

	s.mu.Lock()
	s.listener = listener
	s.mu.Unlock()

	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				s.mu.Lock()
				stopped := s.stopped
				s.mu.Unlock()
				if stopped {
					return
				}
				continue
			}
			go s.handleConnection(conn)
		}
	}()
	return nil
}

func (s *socksServer) Stop() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.stopped = true
	if s.listener != nil {
		err := s.listener.Close()
		s.listener = nil
		return err
	}
	return nil
}

func (s *socksServer) handleConnection(clientConn net.Conn) {
	defer clientConn.Close()

	buf := make([]byte, 258)
	if _, err := io.ReadFull(clientConn, buf[:2]); err != nil {
		return
	}
	if buf[0] != 0x05 {
		return
	}
	numMethods := int(buf[1])
	if _, err := io.ReadFull(clientConn, buf[:numMethods]); err != nil {
		return
	}
	if _, err := clientConn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	if _, err := io.ReadFull(clientConn, buf[:4]); err != nil {
		return
	}
	if buf[0] != 0x05 || buf[1] != 0x01 {
		_, _ = clientConn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var destAddr string
	switch buf[3] {
	case 0x01: // IPv4
		var ip [4]byte
		if _, err := io.ReadFull(clientConn, ip[:]); err != nil {
			return
		}
		var portBytes [2]byte
		if _, err := io.ReadFull(clientConn, portBytes[:]); err != nil {
			return
		}
		port := int(portBytes[0])<<8 | int(portBytes[1])
		destAddr = fmt.Sprintf("%d.%d.%d.%d:%d", ip[0], ip[1], ip[2], ip[3], port)
	case 0x03: // Domain
		var lenBuf [1]byte
		if _, err := io.ReadFull(clientConn, lenBuf[:]); err != nil {
			return
		}
		domainLen := int(lenBuf[0])
		domainBuf := make([]byte, domainLen)
		if _, err := io.ReadFull(clientConn, domainBuf); err != nil {
			return
		}
		var portBytes [2]byte
		if _, err := io.ReadFull(clientConn, portBytes[:]); err != nil {
			return
		}
		port := int(portBytes[0])<<8 | int(portBytes[1])
		destAddr = fmt.Sprintf("%s:%d", string(domainBuf), port)
	default:
		_, _ = clientConn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	remoteConn, err := s.dialer.DialTCP(destAddr)
	if err != nil {
		_, _ = clientConn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remoteConn.Close()

	if _, err := clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	errc := make(chan error, 2)
	go func() {
		_, err := io.Copy(remoteConn, clientConn)
		errc <- err
	}()
	go func() {
		_, err := io.Copy(clientConn, remoteConn)
		errc <- err
	}()
	<-errc
}
