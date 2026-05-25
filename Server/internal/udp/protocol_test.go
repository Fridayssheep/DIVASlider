package udp

import (
	"encoding/binary"
	"testing"

	"divaslider-server/internal/state"
)

func TestParseDivaPacket(t *testing.T) {
	packet := make([]byte, 43)
	packet[0] = 42
	copy(packet[1:4], []byte("DVS"))
	binary.LittleEndian.PutUint32(packet[4:8], 9)
	binary.LittleEndian.PutUint16(packet[8:10], state.ButtonCircle|state.ButtonStart)
	packet[10] = 0x80
	packet[41] = 0x40

	input, err := ParsePacket(packet)
	if err != nil {
		t.Fatal(err)
	}
	if input.Buttons != state.ButtonCircle|state.ButtonStart {
		t.Fatalf("bad buttons: %#x", input.Buttons)
	}
	if input.Slider[0] != 0x80 || input.Slider[31] != 0x40 {
		t.Fatal("bad slider")
	}
}

func TestParseDivaCoinPulse(t *testing.T) {
	packet := make([]byte, 43)
	packet[0] = 42
	copy(packet[1:4], []byte("DVS"))
	binary.LittleEndian.PutUint16(packet[8:10], state.ButtonCoin|state.ButtonTriangle)

	input, err := ParsePacket(packet)
	if err != nil {
		t.Fatal(err)
	}
	if input.Pulse != 0 {
		t.Fatalf("unexpected parser pulse: %#x", input.Pulse)
	}
	if input.Buttons != state.ButtonCoin|state.ButtonTriangle {
		t.Fatalf("bad buttons: %#x", input.Buttons)
	}
}
