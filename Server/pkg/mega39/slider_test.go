package mega39

import "testing"

func TestSliderToMaskUsesCellIndexAsBitIndex(t *testing.T) {
	var slider [32]byte
	slider[0] = 80
	slider[15] = 1
	slider[31] = 255

	mask := SliderToMask(slider)
	want := uint32(0x8000_8001)
	if mask != want {
		t.Fatalf("mask got %#08x want %#08x", mask, want)
	}
}

func TestStickMapperDetectsMovementDirection(t *testing.T) {
	mapper := NewStickMapper()

	var slider [32]byte
	slider[16] = 80
	first := mapper.Axes(slider)
	if first.LeftX != 0 {
		t.Fatalf("first left touch got %#02x want left movement %#02x", first.LeftX, byte(0))
	}

	slider = [32]byte{}
	slider[20] = 80
	second := mapper.Axes(slider)
	if second.LeftX != 0xff {
		t.Fatalf("moved right in upper half got %#02x want right movement %#02x", second.LeftX, byte(0xff))
	}

	third := mapper.Axes([32]byte{})
	if third.LeftX != stickCenter || third.RightX != stickCenter {
		t.Fatalf("released axes got %+v want centered", third)
	}
}
