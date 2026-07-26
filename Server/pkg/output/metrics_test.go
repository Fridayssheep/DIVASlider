package output

import (
	"testing"
	"time"
)

func TestHistogramReportsPercentilesAndMax(t *testing.T) {
	var h Histogram
	for i := 0; i < 97; i++ {
		h.Record(time.Millisecond)
	}
	for i := 0; i < 3; i++ {
		h.Record(9 * time.Millisecond)
	}

	s := h.Snapshot()
	if s.Count != 100 {
		t.Fatalf("count got %d want 100", s.Count)
	}
	if s.MaxNanos != int64(9*time.Millisecond) {
		t.Fatalf("max got %s want 9ms", FormatNanos(s.MaxNanos))
	}
	// The bulk sits at 1ms, so p50 and p95 stay there while p99 reaches into
	// the 3% tail.
	if s.P50Nanos > int64(1100*time.Microsecond) {
		t.Fatalf("p50 got %s want ~1ms", FormatNanos(s.P50Nanos))
	}
	if s.P95Nanos > int64(1100*time.Microsecond) {
		t.Fatalf("p95 got %s want ~1ms", FormatNanos(s.P95Nanos))
	}
	if s.P99Nanos != int64(9*time.Millisecond) {
		t.Fatalf("p99 got %s want the 9ms tail", FormatNanos(s.P99Nanos))
	}
}

func TestHistogramKeepsSubMicrosecondResolution(t *testing.T) {
	var h Histogram
	h.Record(400 * time.Nanosecond)
	h.Record(700 * time.Nanosecond)

	s := h.Snapshot()
	if s.MaxNanos != 700 {
		t.Fatalf("max got %dns want 700ns", s.MaxNanos)
	}
	if s.MeanNanos != 550 {
		t.Fatalf("mean got %dns want 550ns", s.MeanNanos)
	}
}

func TestHistogramEmptySnapshotIsZero(t *testing.T) {
	var h Histogram
	if s := h.Snapshot(); s.Count != 0 || s.MaxNanos != 0 {
		t.Fatalf("empty snapshot got %+v", s)
	}
}

func TestMetricsCountsMissedBeatsAgainstTarget(t *testing.T) {
	m := NewMetrics(time.Millisecond)
	m.RecordPeriod(time.Millisecond)
	m.RecordPeriod(1900 * time.Microsecond)
	m.RecordPeriod(2 * time.Millisecond)
	m.RecordPeriod(16 * time.Millisecond)

	s := m.Snapshot()
	if s.Missed != 2 {
		t.Fatalf("missed got %d want 2 (only beats >= 2x target)", s.Missed)
	}
	if s.RateHz < 100 || s.RateHz > 1000 {
		t.Fatalf("rate got %.1f Hz, want it derived from the mean period", s.RateHz)
	}
}

func TestFormatNanosScalesUnit(t *testing.T) {
	cases := []struct {
		nanos int64
		want  string
	}{
		{0, "0ns"},
		{750, "750ns"},
		{12_300, "12.3us"},
		{1_500_000, "1.500ms"},
	}
	for _, c := range cases {
		if got := FormatNanos(c.nanos); got != c.want {
			t.Fatalf("FormatNanos(%d) got %q want %q", c.nanos, got, c.want)
		}
	}
}
