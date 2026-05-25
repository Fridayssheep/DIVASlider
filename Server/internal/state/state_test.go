package state

import (
	"testing"
	"time"
)

func TestCoinIncrementsCounter(t *testing.T) {
	m := NewManager()
	m.Pulse(ButtonCoin)

	first := m.SnapshotForWrite()
	if first.Coins != 1 {
		t.Fatalf("expected one coin, got %d", first.Coins)
	}

	second := m.SnapshotForWrite()
	if second.Buttons&ButtonCoin != 0 || second.Coins != 1 {
		t.Fatalf("expected stable coin counter without coin button bit, got %+v", second)
	}
}

func TestClearIfExpired(t *testing.T) {
	m := NewManager()
	m.Update(ButtonTriangle|ButtonCoin, [32]byte{1})

	if m.ClearIfExpired(time.Now()) {
		t.Fatal("state should not expire immediately")
	}
	if !m.ClearIfExpired(time.Now().Add(Timeout + time.Millisecond)) {
		t.Fatal("state should expire after timeout")
	}

	snap := m.Snapshot()
	if snap.Connected || snap.Buttons != 0 || snap.Slider[0] != 0 {
		t.Fatalf("state not cleared: %+v", snap)
	}
	if snap.Coins != 1 {
		t.Fatalf("coin counter should survive input timeout, got %d", snap.Coins)
	}
}

func TestLastWriterWins(t *testing.T) {
	m := NewManager()
	var sliderA [32]byte
	var sliderB [32]byte
	sliderA[0] = 1
	sliderB[31] = 2

	m.UpdateSource("web", ButtonTriangle, sliderA)
	m.UpdateSource("phone", ButtonCross, sliderB)

	snap := m.Snapshot()
	if snap.Buttons != ButtonCross {
		t.Fatalf("unexpected buttons: %#x", snap.Buttons)
	}
	if snap.Slider[0] != 0 || snap.Slider[31] != 2 {
		t.Fatalf("unexpected slider: %+v", snap.Slider)
	}
}

func TestClearSourceOnlyAffectsActiveSource(t *testing.T) {
	m := NewManager()

	m.UpdateSource("web", ButtonTriangle, [32]byte{1})
	m.ClearSource("phone")

	if snap := m.Snapshot(); !snap.Connected || snap.Buttons != ButtonTriangle || snap.Slider[0] != 1 {
		t.Fatalf("unexpected snapshot after clearing inactive source: %+v", snap)
	}

	m.ClearSource("web")

	if snap := m.Snapshot(); snap.Connected || snap.Buttons != 0 || snap.Slider[0] != 0 {
		t.Fatalf("active source was not cleared: %+v", snap)
	}
}
