package udp

import (
	"encoding/binary"
	"errors"
)

var ErrUnknownPacket = errors.New("unknown packet")

type Input struct {
	Buttons uint16
	Slider  [32]byte
	Pulse   uint16
}

func ParsePacket(packet []byte) (Input, error) {
	var input Input
	if len(packet) < 4 {
		return input, ErrUnknownPacket
	}

	packetLen := int(packet[0]) + 1
	if packetLen > len(packet) {
		return input, errors.New("truncated packet")
	}
	packet = packet[:packetLen]

	switch string(packet[1:4]) {
	case "DVS":
		return parseDiva(packet)
	default:
		return input, ErrUnknownPacket
	}
}

func parseDiva(packet []byte) (Input, error) {
	var input Input
	if len(packet) < 43 {
		return input, errors.New("short DVS packet")
	}

	input.Buttons = binary.LittleEndian.Uint16(packet[8:10])
	copy(input.Slider[:], packet[10:42])
	return input, nil
}
