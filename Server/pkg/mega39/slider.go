package mega39

const stickCenter byte = 0x80

type StickAxes struct {
	LeftX  byte `json:"leftX"`
	LeftY  byte `json:"leftY"`
	RightX byte `json:"rightX"`
	RightY byte `json:"rightY"`
}

type StickMapper struct {
	left  stickSide
	right stickSide
}

type stickSide struct {
	leftLimit  byte
	rightLimit byte
	value      byte
}

func SliderToMask(slider [32]byte) uint32 {
	var mask uint32
	for i, pressure := range slider {
		if pressure != 0 {
			mask |= 1 << uint(i)
		}
	}
	return mask
}

func NewStickMapper() *StickMapper {
	return &StickMapper{
		left:  stickSide{rightLimit: 0xff, value: stickCenter},
		right: stickSide{rightLimit: 0xff, value: stickCenter},
	}
}

func (m *StickMapper) Axes(slider [32]byte) StickAxes {
	mask := SliderToMask(slider)
	return StickAxes{
		LeftX:  m.left.update(uint16(mask >> 16)),
		LeftY:  stickCenter,
		RightX: m.right.update(uint16(mask)),
		RightY: stickCenter,
	}
}

func (s *stickSide) update(touched uint16) byte {
	if touched == 0 {
		s.leftLimit = 0
		s.rightLimit = 0xff
		s.value = stickCenter
		return s.value
	}

	leftLimit := byte(0)
	rightLimit := byte(0xff)

	for i := byte(0); i < 16; i++ {
		if touched&(1<<(15-i)) != 0 {
			leftLimit = 15 - i
			break
		}
	}

	for i := byte(0); i < 16; i++ {
		if touched&(1<<i) != 0 {
			rightLimit = i
			break
		}
	}

	switch {
	case (leftLimit > s.leftLimit && rightLimit >= s.rightLimit) ||
		(leftLimit >= s.leftLimit && rightLimit > s.rightLimit):
		s.value = 0xff
	case (leftLimit < s.leftLimit && rightLimit <= s.rightLimit) ||
		(leftLimit <= s.leftLimit && rightLimit < s.rightLimit):
		s.value = 0
	}

	s.leftLimit = leftLimit
	s.rightLimit = rightLimit
	return s.value
}
