package legacyvp8channel

import (
	"fmt"

	"github.com/openlibrecommunity/olcrtc/internal/transport"
	currentvp8channel "github.com/openlibrecommunity/olcrtc/internal/transport/vp8channel"
)

const (
	defaultFPS       = 60
	defaultBatchSize = 64
	// defaultMaxBytesPerSec is disabled by default. Earlier defaults capped the
	// fake-VP8 wire rate around 1.2 MB/s, which protected some SFUs from bursts
	// but also throttled real tunnel throughput. Keep the knob for manual safe
	// mode, but do not limit speed unless the user explicitly configures it.
	defaultMaxBytesPerSec = 0
)

// Options tunes the vp8channel transport. Zero values fall back to documented defaults.
type Options struct {
	FPS       int
	BatchSize int
	// MaxBytesPerSec caps the wire byte-rate fed to the video track. Zero means
	// unlimited unless the package default is changed by a build.
	MaxBytesPerSec int
}

// TransportOptions marks Options as belonging to the transport options family.
func (Options) TransportOptions() {}

func optionsFrom(cfg transport.Config) (Options, error) {
	if cfg.Options == nil {
		return Options{}, nil
	}
	switch opts := cfg.Options.(type) {
	case Options:
		return opts, nil
	case currentvp8channel.Options:
		return Options{
			FPS:       opts.FPS,
			BatchSize: opts.BatchSize,
		}, nil
	default:
		return Options{}, fmt.Errorf("%w: legacy vp8channel: got %T", transport.ErrOptionsTypeMismatch, cfg.Options)
	}
}
