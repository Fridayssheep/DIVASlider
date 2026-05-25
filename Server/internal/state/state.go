package state

import (
	"sync"
	"time"
)

const (
	ButtonCircle uint16 = 1 << iota
	ButtonCross
	ButtonSquare
	ButtonTriangle
	ButtonStart
	ButtonTest
	ButtonService
	ButtonCoin
)

const Timeout = 2 * time.Second

type Snapshot struct {
	Sequence      uint32
	UpdatedMillis int64
	Buttons       uint16
	Coins         uint16
	Connected     bool
	Slider        [32]byte
}

type Manager struct {
	mu           sync.Mutex
	snapshot     Snapshot
	activeSource string
	lastInput    time.Time
}

func NewManager() *Manager {
	return &Manager{}
}

func (m *Manager) Update(buttons uint16, slider [32]byte) {
	m.UpdateSource("default", buttons, slider)
}

func (m *Manager) UpdateSource(source string, buttons uint16, slider [32]byte) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if source == "" {
		source = "default"
	}

	now := time.Now()
	if buttons&ButtonCoin != 0 {
		m.snapshot.Coins++
		buttons &^= ButtonCoin
	}

	m.activeSource = source
	m.lastInput = now
	m.snapshot.Sequence++
	m.snapshot.UpdatedMillis = now.UnixMilli()
	m.snapshot.Buttons = buttons
	m.snapshot.Connected = true
	m.snapshot.Slider = slider
}

func (m *Manager) Pulse(button uint16) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if button&ButtonCoin != 0 {
		m.snapshot.Coins++
	}
	m.snapshot.Sequence++
	m.snapshot.UpdatedMillis = time.Now().UnixMilli()
	m.snapshot.Connected = true
}

func (m *Manager) Clear() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.clearLocked(false)
	m.activeSource = ""
	m.lastInput = time.Time{}
}

func (m *Manager) ClearSource(source string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if source == "" {
		source = "default"
	}
	if m.activeSource != source {
		return
	}

	m.activeSource = ""
	m.lastInput = time.Time{}
	m.clearLocked(false)
}

func (m *Manager) ClearIfExpired(now time.Time) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.snapshot.Connected || m.lastInput.IsZero() || now.Sub(m.lastInput) <= Timeout {
		return false
	}

	m.activeSource = ""
	m.lastInput = time.Time{}
	m.clearLocked(false)
	return true
}

func (m *Manager) SnapshotForWrite() Snapshot {
	m.mu.Lock()
	defer m.mu.Unlock()

	return m.snapshot
}

func (m *Manager) Snapshot() Snapshot {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.snapshot
}

func (m *Manager) clearLocked(connected bool) {
	m.snapshot.Sequence++
	m.snapshot.UpdatedMillis = time.Now().UnixMilli()
	m.snapshot.Buttons = 0
	m.snapshot.Connected = connected
	m.snapshot.Slider = [32]byte{}
}
