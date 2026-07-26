package vigem

import (
	"encoding/binary"

	"divaslider-server/internal/state"
	"divaslider-server/pkg/mega39"
)

const (
	ds4DpadUp    = 0x00
	ds4DpadRight = 0x02
	ds4DpadDown  = 0x04
	ds4DpadLeft  = 0x06
	ds4DpadNone  = 0x08

	ds4ButtonSquare   uint16 = 1 << 4
	ds4ButtonCross    uint16 = 1 << 5
	ds4ButtonCircle   uint16 = 1 << 6
	ds4ButtonTriangle uint16 = 1 << 7
	ds4ButtonShare    uint16 = 1 << 12
	ds4ButtonOptions  uint16 = 1 << 13

	// In a real DS4 HID report the low two bits of bSpecial are the PS and
	// touchpad buttons; the upper six are a report counter that increments on
	// every report the pad emits.
	ds4ReportCounterShift = 2
	ds4ReportCounterMask  = 0x3f
)

type DS4Report struct {
	LeftX   byte
	LeftY   byte
	RightX  byte
	RightY  byte
	Buttons uint16
	Special byte
	LeftT   byte
	RightT  byte
}

func DS4ReportFromSnapshot(snapshot state.Snapshot, axes mega39.StickAxes) DS4Report {
	report := DS4Report{
		LeftX:   axes.LeftX,
		LeftY:   axes.LeftY,
		RightX:  axes.RightX,
		RightY:  axes.RightY,
		Buttons: ds4DpadNone,
	}

	if snapshot.Buttons&state.ButtonNav != 0 {
		report.Buttons = (report.Buttons &^ 0x0f) | dpadFromButtons(snapshot.Buttons)
	} else {
		if snapshot.Buttons&state.ButtonSquare != 0 {
			report.Buttons |= ds4ButtonSquare
		}
		if snapshot.Buttons&state.ButtonCross != 0 {
			report.Buttons |= ds4ButtonCross
		}
		if snapshot.Buttons&state.ButtonCircle != 0 {
			report.Buttons |= ds4ButtonCircle
		}
		if snapshot.Buttons&state.ButtonTriangle != 0 {
			report.Buttons |= ds4ButtonTriangle
		}
	}
	if snapshot.Buttons&state.ButtonStart != 0 {
		report.Buttons |= ds4ButtonOptions
	}
	if snapshot.Buttons&state.ButtonService != 0 {
		report.Buttons |= ds4ButtonShare
	}

	return report
}

func dpadFromButtons(buttons uint16) uint16 {
	switch {
	case buttons&state.ButtonTriangle != 0:
		return ds4DpadUp
	case buttons&state.ButtonCircle != 0:
		return ds4DpadRight
	case buttons&state.ButtonCross != 0:
		return ds4DpadDown
	case buttons&state.ButtonSquare != 0:
		return ds4DpadLeft
	default:
		return ds4DpadNone
	}
}

// WithReportCounter stamps the DS4 report counter into bSpecial.
//
// Real hardware advances this on every report, so no two consecutive reports
// are ever byte-identical. Leaving it at zero means a pad that is being held
// still emits the exact same bytes over and over, and ViGEmBus has nothing new
// to hand to the HID stack, so the device goes quiet no matter how often the
// report is submitted. That reads back as a near-zero polling rate to anything
// measuring real HID reports.
func (r DS4Report) WithReportCounter(counter uint8) DS4Report {
	r.Special = (r.Special &^ (ds4ReportCounterMask << ds4ReportCounterShift)) |
		((counter & ds4ReportCounterMask) << ds4ReportCounterShift)
	return r
}

func (r DS4Report) Bytes() []byte {
	out := make([]byte, 10)
	out[0] = r.LeftX
	out[1] = r.LeftY
	out[2] = r.RightX
	out[3] = r.RightY
	binary.LittleEndian.PutUint16(out[4:6], r.Buttons)
	out[6] = r.Special
	out[7] = r.LeftT
	out[8] = r.RightT
	return out
}
