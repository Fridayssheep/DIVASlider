package udp

import (
	"errors"
	"log"
	"net"
	"time"

	"divaslider-server/internal/state"
)

type Server struct {
	Addr   string
	State  *state.Manager
	Logger *log.Logger
}

func (s *Server) ListenAndServe(stop <-chan struct{}) error {
	addr, err := net.ResolveUDPAddr("udp", s.Addr)
	if err != nil {
		return err
	}

	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return err
	}
	defer conn.Close()

	if s.Logger != nil {
		s.Logger.Printf("udp listening on %s", conn.LocalAddr())
	}

	buf := make([]byte, 512)
	for {
		select {
		case <-stop:
			return nil
		default:
		}

		_ = conn.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
		n, remote, err := conn.ReadFromUDP(buf)
		if err != nil {
			var netErr net.Error
			if errors.As(err, &netErr) && netErr.Timeout() {
				continue
			}
			return err
		}

		input, err := ParsePacket(buf[:n])
		if err != nil {
			if !errors.Is(err, ErrUnknownPacket) && s.Logger != nil {
				s.Logger.Printf("udp packet from %s rejected: %v", remote, err)
			}
			continue
		}

		if input.Pulse != 0 {
			s.State.Pulse(input.Pulse)
		} else {
			s.State.UpdateSource(remote.String(), input.Buttons, input.Slider)
		}
	}
}
