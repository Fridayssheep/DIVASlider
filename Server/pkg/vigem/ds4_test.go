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
