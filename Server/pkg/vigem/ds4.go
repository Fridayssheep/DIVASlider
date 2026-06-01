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
