package output

import (
	"fmt"
	"math"
	"sync/atomic"
	"time"
)

// Bucketed so Record costs one atomic add on the 1kHz path. Three tiers,
// because the two things being compared live orders of magnitude apart: a
// single ViGEmBus call lands in the microseconds, while a degraded timer beat
// lands in the tens of milliseconds. Anything past the top tier falls into the
// overflow bucket, where only the observed max carries information.
const (
	ultraBucketWidth  = time.Microsecond
	ultraBucketCount  = 100 // 0..100us
	fineBucketWidth   = 10 * time.Microsecond
	fineBucketCount   = 190 // 100us..2ms
	coarseBucketWidth = 100 * time.Microsecond
	coarseBucketCount = 180 // 2ms..20ms

	ultraLimit  = ultraBucketWidth * ultraBucketCount
	fineLimit   = ultraLimit + fineBucketWidth*fineBucketCount
	coarseLimit = fineLimit + coarseBucketWidth*coarseBucketCount

	bucketCount = ultraBucketCount + fineBucketCount + coarseBucketCount + 1
)

type Histogram struct {
	buckets [bucketCount]atomic.Uint64
	count   atomic.Uint64
	sum     atomic.Int64
	max     atomic.Int64
}

// Nanoseconds, not microseconds: a single ViGEmBus call can land well under a
// microsecond, and truncating that to 0 would hide exactly what we are trying
// to measure.
type HistogramSnapshot struct {
	Count     uint64 `json:"count"`
	MeanNanos int64  `json:"meanNanos"`
	P50Nanos  int64  `json:"p50Nanos"`
	P95Nanos  int64  `json:"p95Nanos"`
	P99Nanos  int64  `json:"p99Nanos"`
	MaxNanos  int64  `json:"maxNanos"`
}

func (h *Histogram) Record(d time.Duration) {
	if d < 0 {
		d = 0
	}

	h.buckets[bucketFor(d)].Add(1)
	h.count.Add(1)
	h.sum.Add(int64(d))

	for {
		old := h.max.Load()
		if int64(d) <= old || h.max.CompareAndSwap(old, int64(d)) {
			break
		}
	}
}

func (h *Histogram) Snapshot() HistogramSnapshot {
	var counts [bucketCount]uint64
	var total uint64
	for i := range h.buckets {
		counts[i] = h.buckets[i].Load()
		total += counts[i]
	}
	if total == 0 {
		return HistogramSnapshot{}
	}

	max := time.Duration(h.max.Load())
	return HistogramSnapshot{
		Count:     total,
		MeanNanos: h.sum.Load() / int64(total),
		P50Nanos:  int64(percentile(&counts, total, max, 0.50)),
		P95Nanos:  int64(percentile(&counts, total, max, 0.95)),
		P99Nanos:  int64(percentile(&counts, total, max, 0.99)),
		MaxNanos:  int64(max),
	}
}

func (h *Histogram) Reset() {
	for i := range h.buckets {
		h.buckets[i].Store(0)
	}
	h.count.Store(0)
	h.sum.Store(0)
	h.max.Store(0)
}

func bucketFor(d time.Duration) int {
	switch {
	case d < ultraLimit:
		return int(d / ultraBucketWidth)
	case d < fineLimit:
		return ultraBucketCount + int((d-ultraLimit)/fineBucketWidth)
	case d < coarseLimit:
		return ultraBucketCount + fineBucketCount + int((d-fineLimit)/coarseBucketWidth)
	default:
		return bucketCount - 1
	}
}

// bucketUpperBound reports the worst case a sample in this bucket could be, so
// percentiles never understate the problem.
func bucketUpperBound(i int) time.Duration {
	switch {
	case i < ultraBucketCount:
		return time.Duration(i+1) * ultraBucketWidth
	case i < ultraBucketCount+fineBucketCount:
		return ultraLimit + time.Duration(i-ultraBucketCount+1)*fineBucketWidth
	case i < bucketCount-1:
		return fineLimit + time.Duration(i-ultraBucketCount-fineBucketCount+1)*coarseBucketWidth
	default:
		return coarseLimit
	}
}

func percentile(counts *[bucketCount]uint64, total uint64, max time.Duration, q float64) time.Duration {
	rank := uint64(math.Ceil(float64(total) * q))
	if rank == 0 {
		rank = 1
	}

	var seen uint64
	for i := range counts {
		seen += counts[i]
		if seen >= rank {
			if bound := bucketUpperBound(i); bound < max {
				return bound
			}
			return max
		}
	}
	return max
}

// Metrics is what tells a slow clock apart from a slow driver: Period is the
// wall time the output loop actually achieved, Write is how much of it the
// adapter (and therefore ViGEmBus) consumed, and Input is how fast fresh state
// is arriving from the phone or browser.
type Metrics struct {
	target time.Duration
	Period Histogram
	Write  Histogram
	Input  Histogram
	missed atomic.Uint64
	failed atomic.Uint64
}

type MetricsSnapshot struct {
	TargetNanos int64             `json:"targetNanos"`
	Period      HistogramSnapshot `json:"period"`
	Write       HistogramSnapshot `json:"write"`
	Input       HistogramSnapshot `json:"input"`
	Missed      uint64            `json:"missed"`
	Failed      uint64            `json:"failed"`
	RateHz      float64           `json:"rateHz"`
}

func NewMetrics(target time.Duration) *Metrics {
	if target <= 0 {
		target = time.Millisecond
	}
	return &Metrics{target: target}
}

// RecordPeriod counts a beat as missed when it ran long enough that a whole
// tick could have fitted inside it.
func (m *Metrics) RecordPeriod(d time.Duration) {
	m.Period.Record(d)
	if d >= 2*m.target {
		m.missed.Add(1)
	}
}

func (m *Metrics) RecordWrite(d time.Duration) { m.Write.Record(d) }
func (m *Metrics) RecordInput(d time.Duration) { m.Input.Record(d) }

// RecordFailure tracks writes the backend rejected. Without it a backend that
// errors out instantly would masquerade as an extremely fast one.
func (m *Metrics) RecordFailure() { m.failed.Add(1) }

func (m *Metrics) Snapshot() MetricsSnapshot {
	period := m.Period.Snapshot()

	var rate float64
	if period.MeanNanos > 0 {
		rate = float64(time.Second) / float64(period.MeanNanos)
	}

	return MetricsSnapshot{
		TargetNanos: int64(m.target),
		Period:      period,
		Write:       m.Write.Snapshot(),
		Input:       m.Input.Snapshot(),
		Missed:      m.missed.Load(),
		Failed:      m.failed.Load(),
		RateHz:      rate,
	}
}

func (m *Metrics) Reset() {
	m.Period.Reset()
	m.Write.Reset()
	m.Input.Reset()
	m.missed.Store(0)
	m.failed.Store(0)
}

func (s HistogramSnapshot) String() string {
	if s.Count == 0 {
		return "no samples"
	}
	return fmt.Sprintf(
		"n=%-7d mean=%-9s p50=%-9s p95=%-9s p99=%-9s max=%s",
		s.Count,
		FormatNanos(s.MeanNanos),
		FormatNanos(s.P50Nanos),
		FormatNanos(s.P95Nanos),
		FormatNanos(s.P99Nanos),
		FormatNanos(s.MaxNanos))
}

// FormatNanos scales the unit to the value so a sub-microsecond driver call and
// a 15ms stalled beat are both readable in the same table.
func FormatNanos(nanos int64) string {
	switch d := time.Duration(nanos); {
	case d < time.Microsecond:
		return fmt.Sprintf("%dns", nanos)
	case d < time.Millisecond:
		return fmt.Sprintf("%.1fus", float64(nanos)/1e3)
	default:
		return fmt.Sprintf("%.3fms", float64(nanos)/1e6)
	}
}
