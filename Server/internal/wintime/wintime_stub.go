//go:build !windows

package wintime

import (
	"sync/atomic"
	"time"
)

// QueryTimerResolution has no meaning outside Windows.
func QueryTimerResolution() Resolution { return Resolution{} }

// Timer wraps time.Ticker. Everything the Windows build does to escape the
// system timer period is Windows specific, so the portable build just ticks.
type Timer struct {
	kind     Kind
	closed   atomic.Bool
	fallback *goTicker
}

func NewTimer(interval time.Duration) *Timer {
	if interval <= 0 {
		interval = time.Millisecond
	}
	return &Timer{kind: KindGoTicker, fallback: newGoTicker(interval)}
}

func (t *Timer) Kind() Kind { return t.kind }

func (t *Timer) Wait() bool { return t.fallback.Wait() }

func (t *Timer) Close() {
	if t.closed.Swap(true) {
		return
	}
	t.fallback.Close()
}

// BoostProcess is a no-op outside Windows.
func BoostProcess() {}

// BoostCurrentThread is a no-op outside Windows.
func BoostCurrentThread() {}
