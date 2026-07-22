package legacyvp8channel

import "testing"

func TestLegacyEpochHeaderRoundTrip(t *testing.T) {
	const (
		token = uint32(0x10203040)
		epoch = uint32(0x50607080)
	)

	header := buildEpochHeader(token, epoch)
	if len(header) != 32 {
		t.Fatalf("legacy header length = %d, want 32", len(header))
	}
	gotToken, gotEpoch, ok := parseEpochHeader(header[:])
	if !ok {
		t.Fatal("legacy header failed its CRC check")
	}
	if gotToken != token || gotEpoch != epoch {
		t.Fatalf("legacy header = (%08x, %08x), want (%08x, %08x)", gotToken, gotEpoch, token, epoch)
	}

	header[len(header)-1] ^= 0xff
	if _, _, ok := parseEpochHeader(header[:]); ok {
		t.Fatal("legacy header accepted a corrupt CRC")
	}
}
