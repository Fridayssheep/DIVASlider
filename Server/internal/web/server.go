package web

import (
	"bufio"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"divaslider-server/internal/shm"
	"divaslider-server/internal/state"
	"divaslider-server/internal/udp"
)

const websocketGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

type Server struct {
	Addr   string
	State  *state.Manager
	Buffer *shm.Buffer
	Logger *log.Logger
}

type inputMessage struct {
	Buttons uint16 `json:"buttons"`
	Slider  []byte `json:"slider"`
	Coin    bool   `json:"coin"`
}

type statusLEDs struct {
	Sequence uint32 `json:"sequence"`
	Slider   []int  `json:"slider"`
	Buttons  []int  `json:"buttons"`
}

func (s *Server) ListenAndServe() error {
	return http.ListenAndServe(s.Addr, s.Handler())
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", s.handleIndex)
	mux.HandleFunc("/ws", s.handleWebSocket)
	mux.HandleFunc("/status", s.handleStatus)
	mux.HandleFunc("/debug/hold", s.handleDebugHold)
	mux.HandleFunc("/debug/slider", s.handleDebugSlider)
	mux.HandleFunc("/debug/led", s.handleDebugLED)

	return mux
}

func (s *Server) handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = io.WriteString(w, indexHTML)
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	type statusResponse struct {
		state.Snapshot
		LEDs statusLEDs `json:"leds"`
	}

	response := statusResponse{Snapshot: s.State.Snapshot()}
	if s.Buffer != nil {
		response.LEDs = makeStatusLEDs(s.Buffer.ReadLEDs())
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(response)
}

func makeStatusLEDs(leds shm.LEDs) statusLEDs {
	return statusLEDs{
		Sequence: leds.Sequence,
		Slider:   bytesToInts(leds.Slider),
		Buttons:  bytesToInts(leds.Buttons),
	}
}

func bytesToInts(values []byte) []int {
	out := make([]int, len(values))
	for i, value := range values {
		out[i] = int(value)
	}
	return out
}

func (s *Server) handleDebugHold(w http.ResponseWriter, r *http.Request) {
	bit, ok := debugButtonBit(r.URL.Query().Get("button"))
	if !ok {
		http.Error(w, "unknown button", http.StatusBadRequest)
		return
	}

	duration, ok := debugDuration(w, r)
	if !ok {
		return
	}

	if s.Logger != nil {
		s.Logger.Printf("debug hold %s for %s", r.URL.Query().Get("button"), duration)
	}

	deadline := time.Now().Add(duration)
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()

	for time.Now().Before(deadline) {
		s.State.UpdateSource("debug:"+r.URL.Query().Get("button"), bit, [32]byte{})
		<-ticker.C
	}
	s.State.ClearSource("debug:" + r.URL.Query().Get("button"))

	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	_, _ = fmt.Fprintf(w, "held %s for %s\n", r.URL.Query().Get("button"), duration)
}

func (s *Server) handleDebugSlider(w http.ResponseWriter, r *http.Request) {
	cell, err := strconv.Atoi(r.URL.Query().Get("cell"))
	if err != nil || cell < 1 || cell > 32 {
		http.Error(w, "cell must be 1..32", http.StatusBadRequest)
		return
	}

	pressure := 80
	if raw := r.URL.Query().Get("pressure"); raw != "" {
		pressure, err = strconv.Atoi(raw)
		if err != nil || pressure < 1 || pressure > 255 {
			http.Error(w, "pressure must be 1..255", http.StatusBadRequest)
			return
		}
	}

	duration, ok := debugDuration(w, r)
	if !ok {
		return
	}

	if s.Logger != nil {
		s.Logger.Printf("debug slider cell %d pressure %d for %s", cell, pressure, duration)
	}

	var slider [32]byte
	slider[cell-1] = byte(pressure)

	deadline := time.Now().Add(duration)
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()

	for time.Now().Before(deadline) {
		s.State.UpdateSource("debug:slider:"+strconv.Itoa(cell), 0, slider)
		<-ticker.C
	}
	s.State.ClearSource("debug:slider:" + strconv.Itoa(cell))

	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	_, _ = fmt.Fprintf(w, "held slider cell %d at pressure %d for %s\n", cell, pressure, duration)
}

func (s *Server) handleDebugLED(w http.ResponseWriter, r *http.Request) {
	if s.Buffer == nil {
		http.Error(w, "shared memory unavailable", http.StatusServiceUnavailable)
		return
	}

	cell, err := strconv.Atoi(r.URL.Query().Get("cell"))
	if err != nil || cell < 1 || cell > 32 {
		http.Error(w, "cell must be 1..32", http.StatusBadRequest)
		return
	}

	value := 255
	if raw := r.URL.Query().Get("value"); raw != "" {
		value, err = strconv.Atoi(raw)
		if err != nil || value < 0 || value > 255 {
			http.Error(w, "value must be 0..255", http.StatusBadRequest)
			return
		}
	}

	rgb := make([]byte, shm.SliderLEDSize)
	rgb[(cell-1)*3] = byte(value)
	rgb[(cell-1)*3+1] = byte(value)
	rgb[(cell-1)*3+2] = byte(value)
	s.Buffer.WriteSliderLEDs(rgb)

	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	_, _ = fmt.Fprintf(w, "set led cell %d to %d\n", cell, value)
}

func debugDuration(w http.ResponseWriter, r *http.Request) (time.Duration, bool) {
	duration := 5 * time.Second
	if raw := r.URL.Query().Get("ms"); raw != "" {
		ms, err := strconv.Atoi(raw)
		if err != nil || ms < 1 || ms > 30000 {
			http.Error(w, "ms must be 1..30000", http.StatusBadRequest)
			return 0, false
		}
		duration = time.Duration(ms) * time.Millisecond
	}

	return duration, true
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		http.Error(w, "expected websocket upgrade", http.StatusBadRequest)
		return
	}

	key := r.Header.Get("Sec-WebSocket-Key")
	if key == "" {
		http.Error(w, "missing Sec-WebSocket-Key", http.StatusBadRequest)
		return
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "hijacking unsupported", http.StatusInternalServerError)
		return
	}

	conn, rw, err := hijacker.Hijack()
	if err != nil {
		return
	}
	defer conn.Close()

	accept := websocketAccept(key)
	_, _ = fmt.Fprintf(rw, "HTTP/1.1 101 Switching Protocols\r\n")
	_, _ = fmt.Fprintf(rw, "Upgrade: websocket\r\n")
	_, _ = fmt.Fprintf(rw, "Connection: Upgrade\r\n")
	_, _ = fmt.Fprintf(rw, "Sec-WebSocket-Accept: %s\r\n\r\n", accept)
	_ = rw.Flush()

	if s.Logger != nil {
		s.Logger.Printf("websocket connected from %s", r.RemoteAddr)
	}
	s.readWebSocket(conn, rw.Reader, r.RemoteAddr)
}

func (s *Server) readWebSocket(conn net.Conn, reader *bufio.Reader, source string) {
	for {
		_ = conn.SetReadDeadline(time.Now().Add(30 * time.Second))
		payload, opcode, err := readFrame(reader)
		if err != nil {
			if !errors.Is(err, io.EOF) && s.Logger != nil {
				s.Logger.Printf("websocket closed: %v", err)
			}
			s.State.ClearSource(source)
			return
		}

		switch opcode {
		case 0x1:
			s.applyJSONPayload(source, payload)
		case 0x2:
			s.applyBinaryPayload(source, payload)
		case 0x9:
			if err := writeFrame(conn, 0xA, payload); err != nil {
				if s.Logger != nil {
					s.Logger.Printf("websocket pong failed: %v", err)
				}
				s.State.ClearSource(source)
				return
			}
		case 0x8:
			s.State.ClearSource(source)
			_ = writeFrame(conn, 0x8, nil)
			return
		}
	}
}

func (s *Server) applyJSONPayload(source string, payload []byte) {
	var msg inputMessage
	if err := json.Unmarshal(payload, &msg); err != nil {
		if s.Logger != nil {
			s.Logger.Printf("bad websocket payload: %v", err)
		}
		return
	}

	var slider [32]byte
	copy(slider[:], msg.Slider)
	if msg.Coin {
		msg.Buttons |= state.ButtonCoin
	}
	s.State.UpdateSource(source, msg.Buttons, slider)
}

func (s *Server) applyBinaryPayload(source string, payload []byte) {
	input, err := udp.ParsePacket(payload)
	if err != nil {
		if s.Logger != nil {
			s.Logger.Printf("bad websocket binary payload: %v", err)
		}
		return
	}

	if input.Pulse != 0 {
		s.State.Pulse(input.Pulse)
	} else {
		s.State.UpdateSource(source, input.Buttons, input.Slider)
	}
}

func websocketAccept(key string) string {
	sum := sha1.Sum([]byte(key + websocketGUID))
	return base64.StdEncoding.EncodeToString(sum[:])
}

func readFrame(r *bufio.Reader) ([]byte, byte, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(r, header); err != nil {
		return nil, 0, err
	}

	opcode := header[0] & 0x0f
	masked := header[1]&0x80 != 0
	length := uint64(header[1] & 0x7f)

	switch length {
	case 126:
		ext := make([]byte, 2)
		if _, err := io.ReadFull(r, ext); err != nil {
			return nil, 0, err
		}
		length = uint64(binary.BigEndian.Uint16(ext))
	case 127:
		ext := make([]byte, 8)
		if _, err := io.ReadFull(r, ext); err != nil {
			return nil, 0, err
		}
		length = binary.BigEndian.Uint64(ext)
	}

	if length > 65536 {
		return nil, 0, errors.New("websocket frame too large")
	}

	var mask [4]byte
	if masked {
		if _, err := io.ReadFull(r, mask[:]); err != nil {
			return nil, 0, err
		}
	}

	payload := make([]byte, int(length))
	if _, err := io.ReadFull(r, payload); err != nil {
		return nil, 0, err
	}

	if masked {
		for i := range payload {
			payload[i] ^= mask[i%4]
		}
	}

	return payload, opcode, nil
}

func writeFrame(w io.Writer, opcode byte, payload []byte) error {
	header := []byte{0x80 | (opcode & 0x0f), 0}
	length := len(payload)

	switch {
	case length < 126:
		header[1] = byte(length)
	case length <= 0xffff:
		header[1] = 126
		header = append(header, byte(length>>8), byte(length))
	default:
		header[1] = 127
		header = append(header,
			byte(uint64(length)>>56),
			byte(uint64(length)>>48),
			byte(uint64(length)>>40),
			byte(uint64(length)>>32),
			byte(uint64(length)>>24),
			byte(uint64(length)>>16),
			byte(uint64(length)>>8),
			byte(uint64(length)))
	}

	if _, err := w.Write(header); err != nil {
		return err
	}
	if len(payload) == 0 {
		return nil
	}
	_, err := w.Write(payload)
	return err
}

func debugButtonBit(name string) (uint16, bool) {
	switch strings.ToLower(name) {
	case "circle":
		return state.ButtonCircle, true
	case "cross":
		return state.ButtonCross, true
	case "square":
		return state.ButtonSquare, true
	case "triangle":
		return state.ButtonTriangle, true
	case "start":
		return state.ButtonStart, true
	case "test":
		return state.ButtonTest, true
	case "service":
		return state.ButtonService, true
	default:
		return 0, false
	}
}

func ButtonList() string {
	buttons := []struct {
		ID    string
		Label string
		Bit   uint16
	}{
		{"triangle", "Triangle", state.ButtonTriangle},
		{"square", "Square", state.ButtonSquare},
		{"cross", "Cross", state.ButtonCross},
		{"circle", "Circle", state.ButtonCircle},
		{"start", "Start", state.ButtonStart},
		{"test", "Test", state.ButtonTest},
		{"service", "Service", state.ButtonService},
	}
	var b strings.Builder
	for _, button := range buttons {
		b.WriteString(`<button data-bit="`)
		b.WriteString(strconv.Itoa(int(button.Bit)))
		b.WriteString(`">`)
		b.WriteString(button.Label)
		b.WriteString(`</button>`)
	}
	b.WriteString(`<button id="coin">Coin</button>`)
	return b.String()
}
