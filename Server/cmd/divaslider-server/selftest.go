package main

import (
	"fmt"
	"runtime"
	"time"

	"divaslider-server/internal/state"
	"divaslider-server/internal/wintime"
	"divaslider-server/pkg/mega39"
	"divaslider-server/pkg/output"
	"divaslider-server/pkg/vigem"
)

// selfTestWindow is long enough for a solid percentile even in the degraded
// case: 5s is 5000 samples at 1kHz and still 320 at 64Hz.
const selfTestWindow = 5 * time.Second

// selfTestMode isolates one variable at a time. The modes run in declaration
// order and each builds on the previous, so the delta between two neighbours
// attributes the improvement to exactly one change.
type selfTestMode struct {
	name    string
	desc    string
	boost   bool // call wintime.BoostProcess first (process wide, irreversible)
	highRes bool // wintime.Timer instead of time.Ticker
	pin     bool // LockOSThread + time critical priority
	drive   bool // actually call into ViGEmBus every beat
	counter bool // advance the DS4 report counter, as the shipping adapter does
}

func selfTestModes() []selfTestMode {
	return []selfTestMode{
		{
			name: "A",
			desc: "time.Ticker, no process boost (this is what ships today)",
		},
		{
			name:  "B",
			desc:  "time.Ticker, after timeBeginPeriod(1) + EcoQoS opt-out",
			boost: true,
		},
		{
			name:    "C",
			desc:    "waitable timer, pinned time-critical thread",
			boost:   true,
			highRes: true,
			pin:     true,
		},
		{
			name:    "D",
			desc:    "C + ViGEmBus update, frozen report counter (old behaviour)",
			boost:   true,
			highRes: true,
			pin:     true,
			drive:   true,
		},
		{
			name:    "E",
			desc:    "C + ViGEmBus update, advancing report counter (as shipped)",
			boost:   true,
			highRes: true,
			pin:     true,
			drive:   true,
			counter: true,
		},
	}
}

func runSelfTest(interval time.Duration) error {
	if interval <= 0 {
		interval = time.Millisecond
	}

	fmt.Println("DIVA Slider output timing self-test")
	fmt.Printf("  target interval  : %s (%.0f Hz)\n", interval, 1/interval.Seconds())
	fmt.Printf("  window per mode  : %s\n", selfTestWindow)
	fmt.Printf("  timer resolution : %s\n", wintime.QueryTimerResolution())
	fmt.Println()

	// Opening the driver up front keeps its one-time setup cost out of the
	// measured window. A missing driver only disables the modes that need it.
	client, clientErr := vigem.Open()
	if clientErr != nil {
		fmt.Printf("  ViGEmBus unavailable, skipping driver modes: %v\n\n", clientErr)
	} else {
		// vigem_target_remove can wedge on shutdown, the same way SliderAdapter
		// guards against. Do not let it hold the report hostage.
		defer func() {
			done := make(chan struct{})
			go func() {
				_ = client.Close()
				close(done)
			}()
			select {
			case <-done:
			case <-time.After(1500 * time.Millisecond):
				fmt.Println("  (ViGEmBus close timed out, exiting anyway)")
			}
		}()
	}

	for _, mode := range selfTestModes() {
		if mode.drive && client == nil {
			fmt.Printf("%s  %s\n     skipped, no ViGEmBus\n\n", mode.name, mode.desc)
			continue
		}

		printSelfTestMode(mode, mode.run(client, interval))
	}

	if client != nil {
		printThroughput(client)
	}

	fmt.Println("  A -> B  isolates the global timer period (timeBeginPeriod)")
	fmt.Println("  B -> C  isolates the waitable timer and thread priority")
	fmt.Println("  F/G     are the only trustworthy per-call costs, see note above")
	fmt.Println()
	fmt.Println("  None of these can see whether ViGEmBus forwards a submitted report")
	fmt.Println("  to the HID stack. They measure the submit call only. To check that,")
	fmt.Println("  read the device with a WebHID based polling rate test.")
	return nil
}

// printThroughput measures ViGEmBus by aggregate rather than per call. Go's
// monotonic clock on Windows is derived from the interrupt timer, so a single
// microsecond-scale call reads back as exactly 0ns; dividing a long run by the
// call count sidesteps that entirely.
func printThroughput(client *vigem.Client) {
	fmt.Println("  ViGEmBus throughput, unthrottled (per-call cost = window / calls)")
	fmt.Println()

	for _, probe := range []struct {
		name    string
		desc    string
		counter bool
	}{
		{"F", "frozen report counter (old behaviour)", false},
		{"G", "advancing report counter (as shipped)", true},
	} {
		calls, elapsed, failed, err := measureThroughput(client, probe.counter, selfTestWindow)
		fmt.Printf("%s  %s\n", probe.name, probe.desc)
		if calls == 0 {
			fmt.Printf("     no calls completed: %v\n\n", err)
			continue
		}

		perCall := elapsed.Nanoseconds() / calls
		fmt.Printf("     calls   %d in %s\n", calls, elapsed.Round(time.Millisecond))
		fmt.Printf("     rate    %.0f calls/s, %s per call\n",
			float64(calls)/elapsed.Seconds(), output.FormatNanos(perCall))
		if failed > 0 {
			fmt.Printf("     FAILED  %d of %d calls rejected: %v\n", failed, calls, err)
		}
		fmt.Println()
	}
}

func measureThroughput(client *vigem.Client, useCounter bool, window time.Duration) (int64, time.Duration, int64, error) {
	// Build the report up front so the loop measures the driver, not the
	// allocation in selfTestReport.
	base := selfTestReport(false)

	var (
		calls    int64
		failed   int64
		firstErr error
		counter  uint8
	)

	start := time.Now()
	deadline := start.Add(window)
	for {
		// Checking the clock every call would be a meaningful share of the work
		// being measured.
		if calls%64 == 0 && !time.Now().Before(deadline) {
			break
		}

		report := base
		if useCounter {
			counter++
			report = report.WithReportCounter(counter)
		}

		if err := client.Update(report); err != nil {
			failed++
			if firstErr == nil {
				firstErr = err
			}
		}
		calls++
	}

	return calls, time.Since(start), failed, firstErr
}

type selfTestResult struct {
	metrics  *output.Metrics
	kind     wintime.Kind
	firstErr error
}

func (mode selfTestMode) run(client *vigem.Client, interval time.Duration) selfTestResult {
	if mode.boost {
		wintime.BoostProcess()
	}

	metrics := output.NewMetrics(interval)
	done := make(chan selfTestResult, 1)

	go func() {
		if mode.pin {
			runtime.LockOSThread()
			defer runtime.UnlockOSThread()
			wintime.BoostCurrentThread()
		}

		wait, backend, stop := mode.clock(interval)
		defer stop()

		var (
			last     time.Time
			deadline = time.Now().Add(selfTestWindow)
			counter  uint8
			firstErr error
		)

		for time.Now().Before(deadline) {
			if !wait() {
				break
			}

			now := time.Now()
			if !last.IsZero() {
				metrics.RecordPeriod(now.Sub(last))
			}
			last = now

			if mode.drive {
				report := selfTestReport(false)
				if mode.counter {
					counter++
					report = report.WithReportCounter(counter)
				}
				// The error matters: a backend that rejects every call returns
				// instantly and would otherwise look blazingly fast.
				err := client.Update(report)
				metrics.RecordWrite(time.Since(now))
				if err != nil {
					metrics.RecordFailure()
					if firstErr == nil {
						firstErr = err
					}
				}
			}
		}

		done <- selfTestResult{metrics: metrics, kind: backend, firstErr: firstErr}
	}()

	return <-done
}

// clock returns a wait function plus the backend it ended up using, so the
// report can show what was actually obtained rather than what was requested.
func (mode selfTestMode) clock(interval time.Duration) (func() bool, wintime.Kind, func()) {
	if mode.highRes {
		timer := wintime.NewTimer(interval)
		return timer.Wait, timer.Kind(), timer.Close
	}

	ticker := time.NewTicker(interval)
	wait := func() bool {
		<-ticker.C
		return true
	}
	return wait, wintime.KindGoTicker, ticker.Stop
}

// selfTestReport builds a report the same way the real adapter does, so the
// driver sees a representative payload.
func selfTestReport(pressed bool) vigem.DS4Report {
	var snapshot state.Snapshot
	if pressed {
		snapshot.Buttons = state.ButtonCircle
	}
	axes := mega39.NewStickMapper().Axes(snapshot.Slider)
	return vigem.DS4ReportFromSnapshot(snapshot, axes)
}

func printSelfTestMode(mode selfTestMode, result selfTestResult) {
	s := result.metrics.Snapshot()

	fmt.Printf("%s  %s\n", mode.name, mode.desc)
	fmt.Printf("     clock   %s\n", result.kind)
	fmt.Printf("     period  %s\n", s.Period)
	fmt.Printf("     rate    %.1f Hz, %d missed beats\n", s.RateHz, s.Missed)
	if mode.drive {
		// A 0ns reading here means "below what Go's Windows monotonic clock can
		// resolve", not "free". Modes F and G below give the real number.
		fmt.Printf("     vigem   %s\n", s.Write)
		if s.Failed > 0 {
			fmt.Printf("     FAILED  %d of %d calls rejected: %v\n", s.Failed, s.Write.Count, result.firstErr)
		}
	}
	fmt.Println()
}
