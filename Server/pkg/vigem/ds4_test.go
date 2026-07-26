package vigem

import (
	"os"
	"testing"

	"divaslider-server/internal/state"
	"divaslider-server/pkg/mega39"
)

func TestDS4ReportFromSnapshot(t *testing.T) {
	report := DS4ReportFromSnapshot(state.Snapshot{
		Buttons: state.ButtonSquare | state.ButtonCross | state.ButtonStart,
	}, mega39.StickAxes{
		LeftX: 0,
		LeftY: 0x80,
	})

	if report.LeftX != 0 || report.LeftY != 0x80 {
		t.Fatalf("bad axes: %+v", report)
	}
	if report.Buttons&ds4ButtonSquare == 0 || report.Buttons&ds4ButtonCross == 0 || report.Buttons&ds4ButtonOptions == 0 {
		t.Fatalf("bad buttons: %#x", report.Buttons)
	}
	if report.Buttons&0x0f != ds4DpadNone {
		t.Fatalf("dpad should be neutral: %#x", report.Buttons)
	}
}

func TestDS4ReportUsesNavLayerForDpad(t *testing.T) {
	report := DS4ReportFromSnapshot(state.Snapshot{
		Buttons: state.ButtonNav | state.ButtonSquare | state.ButtonCross | state.ButtonStart,
	}, mega39.StickAxes{
		LeftX:  0x80,
		LeftY:  0x80,
		RightX: 0x80,
		RightY: 0x80,
	})

	if report.Buttons&ds4ButtonSquare != 0 || report.Buttons&ds4ButtonCross != 0 {
		t.Fatalf("nav layer should suppress shape buttons: %#x", report.Buttons)
	}
	if report.Buttons&0x0f != ds4DpadDown {
		t.Fatalf("expected cross to map to dpad down, got %#x", report.Buttons&0x0f)
	}
	if report.Buttons&ds4ButtonOptions == 0 {
		t.Fatalf("start should remain options in nav layer: %#x", report.Buttons)
	}
}

func TestWithReportCounterOnlyTouchesTheCounterBits(t *testing.T) {
	// PS and touchpad live in the low two bits and must survive untouched.
	base := DS4Report{Special: 0x03}

	if got := base.WithReportCounter(0).Special; got != 0x03 {
		t.Fatalf("counter 0 got %#02x want 0x03", got)
	}
	if got := base.WithReportCounter(1).Special; got != 0x07 {
		t.Fatalf("counter 1 got %#02x want 0x07", got)
	}
	// The counter is six bits wide, so 64 wraps back to 0.
	if got := base.WithReportCounter(64).Special; got != 0x03 {
		t.Fatalf("counter 64 should wrap, got %#02x want 0x03", got)
	}
	if got := base.WithReportCounter(63).Special; got != 0xff {
		t.Fatalf("counter 63 got %#02x want 0xff", got)
	}
}

func TestWithReportCounterMakesConsecutiveReportsDiffer(t *testing.T) {
	report := DS4ReportFromSnapshot(state.Snapshot{}, mega39.StickAxes{
		LeftX: 0x80, LeftY: 0x80, RightX: 0x80, RightY: 0x80,
	})

	// This is the whole point: an idle pad must still produce distinct bytes,
	// or ViGEmBus has nothing new to forward to the HID stack.
	seen := map[string]bool{}
	for i := 0; i < 64; i++ {
		key := string(report.WithReportCounter(uint8(i)).Bytes())
		if seen[key] {
			t.Fatalf("counter %d produced a duplicate report", i)
		}
		seen[key] = true
	}
}

func TestViGEmDS4Integration(t *testing.T) {
	if os.Getenv("DIVASLIDER_TEST_VIGEM") != "1" {
		t.Skip("set DIVASLIDER_TEST_VIGEM=1 to create a real ViGEmBus DS4 target")
	}

	client, err := Open()
	if err != nil {
		t.Fatalf("open ViGEmBus DS4 target: %v", err)
	}
	defer client.Close()

	report := DS4ReportFromSnapshot(state.Snapshot{
		Buttons: state.ButtonTriangle,
	}, mega39.StickAxes{
		LeftX:  0xff,
		LeftY:  0x80,
		RightX: 0x80,
		RightY: 0x80,
	})

	if err := client.Update(report); err != nil {
		t.Fatalf("update ViGEmBus DS4 target: %v", err)
	}
}
