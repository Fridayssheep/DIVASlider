package shm

import (
	"encoding/binary"
	"testing"

	"divaslider-server/internal/state"
)

func TestWriteSnapshotLayout(t *testing.T) {
	buf, err := Open()
	if err != nil {
		t.Fatal(err)
	}
	defer buf.Close()

	var slider [32]byte
	slider[0] = 0x80
	slider[31] = 0x40

	buf.WriteSnapshot(state.Snapshot{
		Sequence:      7,
		UpdatedMillis: 1234,
		Buttons:       state.ButtonTriangle | state.ButtonStart,
		Coins:         3,
		Connected:     true,
		Slider:        slider,
	})

	data := buf.BytesForTest()
	if string(data[:4]) != "DIVA" {
		t.Fatalf("bad magic: %q", data[:4])
	}
	if binary.LittleEndian.Uint16(data[4:6]) != 1 {
		t.Fatal("bad version")
	}
	if binary.LittleEndian.Uint32(data[8:12]) != 2 {
		t.Fatal("bad sequence")
	}
	if binary.LittleEndian.Uint16(data[20:22]) != state.ButtonTriangle|state.ButtonStart {
		t.Fatal("bad buttons")
	}
	if data[22] != 1 || data[24] != 0x80 || data[55] != 0x40 {
		t.Fatal("bad connected or slider fields")
	}
	if binary.LittleEndian.Uint16(data[56:58]) != 3 {
		t.Fatal("bad coin counter")
	}
}

func TestWriteSnapshotClearsConnectedFlag(t *testing.T) {
	buf, err := Open()
	if err != nil {
		t.Fatal(err)
	}
	defer buf.Close()

	buf.WriteSnapshot(state.Snapshot{Connected: true})
	buf.WriteSnapshot(state.Snapshot{})

	data := buf.BytesForTest()
	if data[22] != 0 {
		t.Fatalf("connected flag was not cleared: %d", data[22])
	}
}

func TestReadLEDs(t *testing.T) {
	buf, err := Open()
	if err != nil {
		t.Fatal(err)
	}
	defer buf.Close()

	data := buf.BytesForTest()
	binary.LittleEndian.PutUint32(data[OffsetLEDSequence:OffsetLEDSequence+4], 9)
	data[OffsetSliderLEDs] = 10
	data[OffsetSliderLEDs+1] = 20
	data[OffsetSliderLEDs+2] = 30
	data[OffsetButtonLEDs+9] = 255

	leds := buf.ReadLEDs()
	if leds.Sequence != 9 {
		t.Fatalf("bad led sequence: %d", leds.Sequence)
	}
	if len(leds.Slider) != SliderLEDSize || leds.Slider[0] != 10 || leds.Slider[1] != 20 || leds.Slider[2] != 30 {
		t.Fatalf("bad slider leds: %+v", leds.Slider[:3])
	}
	if len(leds.Buttons) != ButtonLEDSize || leds.Buttons[9] != 255 {
		t.Fatalf("bad button leds: %+v", leds.Buttons)
	}
}

func TestWriteSliderLEDs(t *testing.T) {
	buf, err := Open()
	if err != nil {
		t.Fatal(err)
	}
	defer buf.Close()

	rgb := make([]byte, SliderLEDSize)
	rgb[3] = 11
	rgb[4] = 22
	rgb[5] = 33
	before := buf.ReadLEDs()
	buf.WriteSliderLEDs(rgb)

	leds := buf.ReadLEDs()
	if leds.Sequence != before.Sequence+1 {
		t.Fatalf("bad led sequence: got %d want %d", leds.Sequence, before.Sequence+1)
	}
	if leds.Slider[3] != 11 || leds.Slider[4] != 22 || leds.Slider[5] != 33 {
		t.Fatalf("bad slider led write: %+v", leds.Slider[3:6])
	}
}
