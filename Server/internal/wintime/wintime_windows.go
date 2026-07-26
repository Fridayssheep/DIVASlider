//go:build windows

package wintime

import (
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

const (
	timerAllAccess = 0x1F0003
	// CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, Windows 10 1803 and newer.
	createWaitableTimerHighResolution = 0x00000002
	infinite                          = 0xFFFFFFFF

	threadPriorityTimeCritical = 15

	// PROCESS_INFORMATION_CLASS.ProcessPowerThrottling
	processPowerThrottling        = 4
	powerThrottlingCurrentVersion = 1
	powerThrottlingExecutionSpeed = 0x1
	// PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION, Windows 11 and newer.
	powerThrottlingIgnoreTimerResolution = 0x4
)

var (
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	winmm    = syscall.NewLazyDLL("winmm.dll")
	ntdll    = syscall.NewLazyDLL("ntdll.dll")

	procCreateWaitableTimerEx = kernel32.NewProc("CreateWaitableTimerExW")
	procSetWaitableTimer      = kernel32.NewProc("SetWaitableTimer")
	procWaitForSingleObject   = kernel32.NewProc("WaitForSingleObject")
	procCloseHandle           = kernel32.NewProc("CloseHandle")
	procGetCurrentThread      = kernel32.NewProc("GetCurrentThread")
	procSetThreadPriority     = kernel32.NewProc("SetThreadPriority")
	procGetCurrentProcess     = kernel32.NewProc("GetCurrentProcess")
	procSetProcessInformation = kernel32.NewProc("SetProcessInformation")

	procTimeBeginPeriod = winmm.NewProc("timeBeginPeriod")

	procNtQueryTimerResolution = ntdll.NewProc("NtQueryTimerResolution")
)

// QueryTimerResolution reports the current system timer period. A Current of
// ~15.625ms is the smoking gun for a degraded time.Ticker: every Go timer
// wakeup on this machine is quantized to that period.
func QueryTimerResolution() Resolution {
	if err := procNtQueryTimerResolution.Find(); err != nil {
		return Resolution{}
	}

	// NtQueryTimerResolution reports in 100ns units, and names the coarsest
	// value "minimum" because it is the minimum *rate*.
	var coarsest, finest, current uint32
	status, _, _ := procNtQueryTimerResolution.Call(
		uintptr(unsafe.Pointer(&coarsest)),
		uintptr(unsafe.Pointer(&finest)),
		uintptr(unsafe.Pointer(&current)),
	)
	if status != 0 {
		return Resolution{}
	}

	return Resolution{
		Supported: true,
		Coarsest:  time.Duration(coarsest) * 100,
		Finest:    time.Duration(finest) * 100,
		Current:   time.Duration(current) * 100,
	}
}

// Timer ticks at a fixed interval, backed by a Windows waitable timer so its
// accuracy does not depend on the global timer resolution.
//
// Wait blocks for at most one interval, so a caller watching a context will
// notice cancellation within that interval. Close must not run concurrently
// with Wait.
type Timer struct {
	interval time.Duration
	kind     Kind
	handle   uintptr
	next     time.Time
	closed   atomic.Bool
	fallback *goTicker
}

// NewTimer always returns a usable Timer, degrading through the backends in
// order. Check Kind to see what was actually obtained.
func NewTimer(interval time.Duration) *Timer {
	if interval <= 0 {
		interval = time.Millisecond
	}

	if handle, kind, ok := createWaitableTimer(); ok {
		return &Timer{interval: interval, kind: kind, handle: handle, next: time.Now()}
	}
	return &Timer{interval: interval, kind: KindGoTicker, fallback: newGoTicker(interval)}
}

func createWaitableTimer() (uintptr, Kind, bool) {
	handle, _, _ := procCreateWaitableTimerEx.Call(
		0, 0, createWaitableTimerHighResolution, timerAllAccess)
	if handle != 0 {
		return handle, KindHighResolution, true
	}

	// Kernels older than 1803 reject the high resolution flag outright. Retry
	// without it, and lift the global timer period so the plain waitable timer
	// is not quantized to 15.625ms.
	handle, _, _ = procCreateWaitableTimerEx.Call(0, 0, 0, timerAllAccess)
	if handle == 0 {
		return 0, KindGoTicker, false
	}
	beginPeriod()
	return handle, KindWaitable, true
}

func (t *Timer) Kind() Kind { return t.kind }

// Wait blocks until the next tick, returning false once the Timer is closed.
func (t *Timer) Wait() bool {
	if t.fallback != nil {
		return t.fallback.Wait()
	}
	if t.closed.Load() {
		return false
	}

	// Schedule against an absolute deadline so the loop does not accumulate
	// drift from the time spent in the body.
	now := time.Now()
	t.next = t.next.Add(t.interval)
	if !t.next.After(now) {
		// Already behind: resync to now rather than firing a catch-up burst of
		// ticks that the consumer would just collapse anyway.
		t.next = now.Add(t.interval)
	}

	// Negative due time means relative, in 100ns units. Period 0 keeps this a
	// one-shot; periodic waitable timers are not guaranteed to hold high
	// resolution, so re-arm every tick instead.
	due := -int64(t.next.Sub(now)) / 100
	armed, _, _ := procSetWaitableTimer.Call(
		t.handle, uintptr(unsafe.Pointer(&due)), 0, 0, 0, 0)
	if armed == 0 {
		time.Sleep(t.next.Sub(now))
		return !t.closed.Load()
	}

	procWaitForSingleObject.Call(t.handle, infinite)
	return !t.closed.Load()
}

func (t *Timer) Close() {
	if t.closed.Swap(true) {
		return
	}
	if t.fallback != nil {
		t.fallback.Close()
		return
	}
	procCloseHandle.Call(t.handle)
}

var beginPeriodOnce sync.Once

func beginPeriod() {
	beginPeriodOnce.Do(func() {
		// Never paired with timeEndPeriod: the runner can be restarted many
		// times inside ControlCenter, and Windows releases the request when the
		// process exits anyway.
		procTimeBeginPeriod.Call(1)
	})
}

// BoostProcess opts the process out of the Windows scheduling behaviour that
// makes a background input server jittery. Safe to call repeatedly.
func BoostProcess() {
	beginPeriod()

	// Windows 11 applies EcoQoS to background processes: threads get parked on
	// efficiency cores and timeBeginPeriod requests are ignored. The server is
	// always in the background while the game has focus, so opt out of both.
	//
	// Two separate calls on purpose. IGNORE_TIMER_RESOLUTION is Windows 11 only,
	// and a combined call fails as a whole on Windows 10, which would take the
	// EXECUTION_SPEED opt-out down with it.
	setPowerThrottling(powerThrottlingExecutionSpeed)
	setPowerThrottling(powerThrottlingIgnoreTimerResolution)
}

func setPowerThrottling(mask uint32) {
	if err := procSetProcessInformation.Find(); err != nil {
		return
	}

	// PROCESS_POWER_THROTTLING_STATE. A zero StateMask against a set ControlMask
	// means "disable this throttling for me".
	state := struct {
		Version     uint32
		ControlMask uint32
		StateMask   uint32
	}{
		Version:     powerThrottlingCurrentVersion,
		ControlMask: mask,
	}

	process, _, _ := procGetCurrentProcess.Call()
	procSetProcessInformation.Call(
		process,
		processPowerThrottling,
		uintptr(unsafe.Pointer(&state)),
		unsafe.Sizeof(state),
	)
}

// BoostCurrentThread raises the calling thread to time critical priority. The
// caller must already hold runtime.LockOSThread, otherwise Go is free to move
// the goroutine onto some other, unboosted thread.
func BoostCurrentThread() {
	thread, _, _ := procGetCurrentThread.Call()
	procSetThreadPriority.Call(thread, threadPriorityTimeCritical)
}
