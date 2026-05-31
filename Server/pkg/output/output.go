package output

import (
	"context"
	"fmt"
	"log"
	"sync"
	"time"

	"divaslider-server/internal/shm"
	"divaslider-server/internal/state"
	"divaslider-server/pkg/mega39"
	"divaslider-server/pkg/vigem"
)

type Adapter interface {
	Name() string
	Write(state.Snapshot)
	Close() error
	Status() Status
}

type Status struct {
	Name          string            `json:"name"`
	Ready         bool              `json:"ready"`
	Message       string            `json:"message"`
	JoystickAxes  *mega39.StickAxes `json:"joystickAxes,omitempty"`
	LastUpdatedMS int64             `json:"lastUpdatedMs,omitempty"`
}

type Manager struct {
	adapters []Adapter
}

func NewManager(adapters ...Adapter) *Manager {
	return &Manager{adapters: adapters}
}

func (m *Manager) Run(ctx context.Context, stateManager *state.Manager, interval time.Duration) {
	if interval <= 0 {
		interval = time.Millisecond
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			snapshot := stateManager.SnapshotForWrite()
			for _, adapter := range m.adapters {
				adapter.Write(snapshot)
			}
		}
	}
}

func (m *Manager) Close() error {
	var firstErr error
	for _, adapter := range m.adapters {
		if err := adapter.Close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

func (m *Manager) Statuses() []Status {
	statuses := make([]Status, 0, len(m.adapters))
	for _, adapter := range m.adapters {
		statuses = append(statuses, adapter.Status())
	}
	return statuses
}

type SharedMemoryAdapter struct {
	buffer *shm.Buffer
}

func NewSharedMemoryAdapter(buffer *shm.Buffer) *SharedMemoryAdapter {
	return &SharedMemoryAdapter{buffer: buffer}
}

func (a *SharedMemoryAdapter) Name() string {
	return "DLL / TLAC shared memory"
}

func (a *SharedMemoryAdapter) Write(snapshot state.Snapshot) {
	if a.buffer != nil {
		a.buffer.WriteSnapshot(snapshot)
	}
}

func (a *SharedMemoryAdapter) Close() error {
	return nil
}

func (a *SharedMemoryAdapter) Status() Status {
	return Status{Name: a.Name(), Ready: a.buffer != nil, Message: "Local\\DIVASLIDER_SHARED_BUFFER"}
}

type SliderAdapter struct {
	stick  *mega39.StickMapper
	vigem  *vigem.Client
	last   Status
	lastMu sync.Mutex
	logger *log.Logger
}

func NewSliderAdapter(logger *log.Logger) (*SliderAdapter, error) {
	adapter := &SliderAdapter{
		stick:  mega39.NewStickMapper(),
		logger: logger,
		last: Status{
			Name:    "Slider emulator",
			Ready:   false,
			Message: "No output backend is attached yet.",
		},
	}
	client, err := vigem.Open()
	if err != nil {
		if logger != nil {
			logger.Printf("ViGEmBus unavailable: %v", err)
		}
		return nil, fmt.Errorf("open ViGEmBus DS4 output: %w", err)
	}
	adapter.vigem = client
	return adapter, nil
}

func (a *SliderAdapter) Name() string {
	return "Slider emulator"
}

func (a *SliderAdapter) Write(snapshot state.Snapshot) {
	status := Status{
		Name:          a.Name(),
		Ready:         false,
		LastUpdatedMS: snapshot.UpdatedMillis,
	}

	axes := a.stick.Axes(snapshot.Slider)
	status.JoystickAxes = &axes
	if a.vigem == nil {
		status.Message = "ViGEmBus is not connected."
		a.setStatus(status)
		return
	}
	if err := a.vigem.Update(vigem.DS4ReportFromSnapshot(snapshot, axes)); err != nil {
		status.Message = "ViGEmBus DS4 update failed: " + err.Error()
		a.setStatus(status)
		return
	}
	status.Ready = true
	status.Message = "Joystick slider sent to ViGEmBus DS4."
	a.setStatus(status)
}

func (a *SliderAdapter) setStatus(status Status) {
	a.lastMu.Lock()
	a.last = status
	a.lastMu.Unlock()
}

func (a *SliderAdapter) Close() error {
	if a.vigem == nil {
		return nil
	}

	done := make(chan error, 1)
	go func() {
		done <- a.vigem.Close()
	}()

	select {
	case err := <-done:
		return err
	case <-time.After(1500 * time.Millisecond):
		if a.logger != nil {
			a.logger.Printf("ViGEmBus DS4 close timed out; continuing shutdown")
		}
		return fmt.Errorf("ViGEmBus DS4 close timed out")
	}
}

func (a *SliderAdapter) Status() Status {
	a.lastMu.Lock()
	defer a.lastMu.Unlock()
	return a.last
}
