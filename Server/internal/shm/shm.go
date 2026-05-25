package shm

import (
	"encoding/binary"

	"divaslider-server/internal/state"
)

const (
	Name              = "Local\\DIVASLIDER_SHARED_BUFFER"
	Size              = 256
	OffsetLEDSequence = 60
	OffsetSliderLEDs  = 64
	SliderLEDSize     = 32 * 3
	OffsetButtonLEDs  = OffsetSliderLEDs + SliderLEDSize
	ButtonLEDSize     = 10
)

type Buffer struct {
	data          []byte
	writeSequence uint32
	done          func() error
}

type LEDs struct {
	Sequence uint32 `json:"sequence"`
	Slider   []byte `json:"slider"`
	Buttons  []byte `json:"buttons"`
}

func (b *Buffer) WriteSnapshot(s state.Snapshot) {
	if len(b.data) < Size {
		return
	}

	b.writeSequence += 2
	if b.writeSequence == 0 {
		b.writeSequence = 2
	}
	sequence := b.writeSequence
	binary.LittleEndian.PutUint32(b.data[8:12], sequence|1)
	copy(b.data[0:4], []byte("DIVA"))
	binary.LittleEndian.PutUint16(b.data[4:6], 1)
	binary.LittleEndian.PutUint16(b.data[6:8], Size)
	binary.LittleEndian.PutUint64(b.data[12:20], uint64(s.UpdatedMillis))
	binary.LittleEndian.PutUint16(b.data[20:22], s.Buttons)
	b.data[22] = 0
	if s.Connected {
		b.data[22] = 1
	}
	copy(b.data[24:56], s.Slider[:])
	binary.LittleEndian.PutUint16(b.data[56:58], s.Coins)
	binary.LittleEndian.PutUint32(b.data[8:12], sequence)
}

func (b *Buffer) BytesForTest() []byte {
	return b.data
}

func (b *Buffer) ReadLEDs() LEDs {
	if len(b.data) < OffsetButtonLEDs+ButtonLEDSize {
		return LEDs{
			Slider:  make([]byte, SliderLEDSize),
			Buttons: make([]byte, ButtonLEDSize),
		}
	}

	slider := make([]byte, SliderLEDSize)
	buttons := make([]byte, ButtonLEDSize)
	copy(slider, b.data[OffsetSliderLEDs:OffsetSliderLEDs+SliderLEDSize])
	copy(buttons, b.data[OffsetButtonLEDs:OffsetButtonLEDs+ButtonLEDSize])

	return LEDs{
		Sequence: binary.LittleEndian.Uint32(b.data[OffsetLEDSequence : OffsetLEDSequence+4]),
		Slider:   slider,
		Buttons:  buttons,
	}
}

func (b *Buffer) WriteSliderLEDs(rgb []byte) {
	if len(b.data) < OffsetSliderLEDs+SliderLEDSize {
		return
	}

	leds := make([]byte, SliderLEDSize)
	copy(leds, rgb)
	copy(b.data[OffsetSliderLEDs:OffsetSliderLEDs+SliderLEDSize], leds)
	binary.LittleEndian.PutUint32(
		b.data[OffsetLEDSequence:OffsetLEDSequence+4],
		binary.LittleEndian.Uint32(b.data[OffsetLEDSequence:OffsetLEDSequence+4])+1)
}

func (b *Buffer) Close() error {
	if b.done == nil {
		return nil
	}
	return b.done()
}
