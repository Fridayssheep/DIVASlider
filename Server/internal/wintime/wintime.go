// Package wintime provides the timing primitives the 1kHz output loop needs.
//
// Go's timers on Windows bottom out in netpoll -> GetQueuedCompletionStatusEx,
// whose timeout is an integer number of milliseconds quantized to the system
// timer period. That period defaults to 15.625ms, and since Go 1.16 the runtime
// no longer raises it unconditionally, so a 1ms time.Ticker can silently run at
// ~64Hz. Worse, the effective rate depends on whether some *other* process on
// the machine happens to have raised the global resolution, which is why the
// symptom comes and goes.
//
// On Windows this package sidesteps that with a waitable timer; everywhere else
// it degrades to time.Ticker so the server and its tests still build and run.
package wintime

import (
	"fmt"
	"sync"
	"time"
)

// Kind identifies which backend a Timer ended up using, so callers can log what
// they actually got rather than what they asked for.
type Kind string

const (
	KindHighResolution Kind = "high-resolution waitable timer"
	KindWaitable       Kind = "waitable timer + timeBeginPeriod(1)"
	KindGoTicker       Kind = "time.Ticker"
)

// Resolution reports the system timer period. Note that the Windows API calls
// the coarsest value "minimum" and the finest "maximum"; the names here follow
// what the numbers actually mean.
type Resolution struct {
	Supported bool
	Coarsest  time.Duration
	Finest    time.Duration
	Current   time.Duration
}

func (r Resolution) String() string {
	if !r.Supported {
		return "unavailable"
	}
	return fmt.Sprintf(
		"current=%s finest=%s coarsest=%s",
		formatMillis(r.Current),
		formatMillis(r.Finest),
		formatMillis(r.Coarsest))
}

func formatMillis(d time.Duration) string {
	return fmt.Sprintf("%.3fms", float64(d)/float64(time.Millisecond))
}

// goTicker is the portable fallback backend, shared by both platform variants.
type goTicker struct {
	ticker *time.Ticker
	stop   chan struct{}
	once   sync.Once
}

func newGoTicker(interval time.Duration) *goTicker {
	return &goTicker{ticker: time.NewTicker(interval), stop: make(chan struct{})}
}

// Wait blocks for one interval. Ticker.Stop does not close the channel, so
// closing has to travel over a separate channel or Wait would block forever.
func (t *goTicker) Wait() bool {
	select {
	case <-t.ticker.C:
		return true
	case <-t.stop:
		return false
	}
}

func (t *goTicker) Close() {
	t.once.Do(func() {
		t.ticker.Stop()
		close(t.stop)
	})
}
