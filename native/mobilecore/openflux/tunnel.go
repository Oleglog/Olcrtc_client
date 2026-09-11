package openflux

import (
	"fmt"
	"net"
	"sync/atomic"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

type tcpTunnel struct {
	gvisorStack *stack.Stack
	tunnelEP    *tunnelLinkEndpoint
	transport   transport
}

func newTCPTunnel(trans transport) *tcpTunnel {
	t := &tcpTunnel{
		transport: trans,
	}

	t.gvisorStack = stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol},
	})

	_ = t.gvisorStack.SetTransportProtocolOption(tcp.ProtocolNumber,
		&tcpip.TCPReceiveBufferSizeRangeOption{Min: 65536, Default: 262144, Max: 4194304})
	_ = t.gvisorStack.SetTransportProtocolOption(tcp.ProtocolNumber,
		&tcpip.TCPSendBufferSizeRangeOption{Min: 65536, Default: 262144, Max: 4194304})

	ep := &tunnelLinkEndpoint{
		onOutgoingPacket: func(data []byte) {
			_ = trans.Send(data)
		},
	}
	t.tunnelEP = ep

	tunnelNIC := tcpip.NICID(1)
	_ = t.gvisorStack.CreateNIC(tunnelNIC, ep)

	var ipBytes [4]byte
	fmt.Sscanf("10.0.0.2", "%d.%d.%d.%d", &ipBytes[0], &ipBytes[1], &ipBytes[2], &ipBytes[3])
	protoAddr := tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4(ipBytes).WithPrefix(),
	}
	_ = t.gvisorStack.AddProtocolAddress(tunnelNIC, protoAddr, stack.AddressProperties{})

	t.gvisorStack.SetRouteTable([]tcpip.Route{
		{
			Destination: header.IPv4EmptySubnet(),
			NIC:         tunnelNIC,
		},
	})

	trans.Receive(func(data []byte) {
		ep.InjectInbound(data)
	})

	return t
}

func (t *tcpTunnel) DialTCP(address string) (net.Conn, error) {
	tcpAddr, err := net.ResolveTCPAddr("tcp", address)
	if err != nil {
		return nil, fmt.Errorf("resolve: %w", err)
	}
	ip := tcpAddr.IP.To4()
	if ip == nil {
		return nil, fmt.Errorf("IPv6 not supported")
	}

	return gonet.DialTCP(t.gvisorStack, tcpip.FullAddress{
		NIC:  1,
		Addr: tcpip.AddrFrom4([4]byte{ip[0], ip[1], ip[2], ip[3]}),
		Port: uint16(tcpAddr.Port),
	}, ipv4.ProtocolNumber)
}

type tunnelLinkEndpoint struct {
	dispatcher       stack.NetworkDispatcher
	onOutgoingPacket func([]byte)
	packetIn         atomic.Uint64
	packetOut        atomic.Uint64
}

func (e *tunnelLinkEndpoint) InjectInbound(data []byte) {
	e.packetIn.Add(1)
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
		Payload: buffer.MakeWithData(append([]byte{}, data...)),
	})
	if e.dispatcher != nil {
		e.dispatcher.DeliverNetworkPacket(ipv4.ProtocolNumber, pkt)
	}
}

func (e *tunnelLinkEndpoint) WritePackets(pkts stack.PacketBufferList) (int, tcpip.Error) {
	n := 0
	for _, pkt := range pkts.AsSlice() {
		data := pkt.ToView().ToSlice()
		e.packetOut.Add(1)
		if e.onOutgoingPacket != nil {
			e.onOutgoingPacket(data)
		}
		n++
	}
	return n, nil
}

func (e *tunnelLinkEndpoint) MTU() uint32                                 { return 1500 }
func (e *tunnelLinkEndpoint) MaxHeaderLength() uint16                      { return 0 }
func (e *tunnelLinkEndpoint) LinkAddress() tcpip.LinkAddress               { return "\x02\x00\x00\x00\x00\x01" }
func (e *tunnelLinkEndpoint) Capabilities() stack.LinkEndpointCapabilities { return stack.CapabilityNone }
func (e *tunnelLinkEndpoint) Attach(dispatcher stack.NetworkDispatcher)    { e.dispatcher = dispatcher }
func (e *tunnelLinkEndpoint) IsAttached() bool                             { return e.dispatcher != nil }
func (e *tunnelLinkEndpoint) Wait()                                        {}
func (e *tunnelLinkEndpoint) ARPHardwareType() header.ARPHardwareType      { return header.ARPHardwareNone }
func (e *tunnelLinkEndpoint) AddHeader(*stack.PacketBuffer)                {}
func (e *tunnelLinkEndpoint) Close()                                       {}
func (e *tunnelLinkEndpoint) SetMTU(uint32)                                {}
func (e *tunnelLinkEndpoint) SetLinkAddress(tcpip.LinkAddress)             {}
func (e *tunnelLinkEndpoint) ParseHeader(*stack.PacketBuffer) bool         { return true }
func (e *tunnelLinkEndpoint) SetOnCloseAction(func())                      {}
