package runner

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"divaslider-server/internal/shm"
	"divaslider-server/internal/state"
	"divaslider-server/internal/udp"
	"divaslider-server/internal/web"
	"divaslider-server/pkg/inputmethod"
	"divaslider-server/pkg/output"
)

type Runner struct {
	cfg     inputmethod.Config
	logger  *log.Logger
	manager *state.Manager
	buffer  *shm.Buffer
	outputs *output.Manager
	server  *http.Server

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup
	mu     sync.Mutex
}

type Status struct {
	Running bool               `json:"running"`
	Config  inputmethod.Config `json:"config"`
	Input   state.Snapshot     `json:"input"`
	Outputs []output.Status    `json:"outputs"`
	HTTPURL string             `json:"httpUrl"`
}

func New(cfg inputmethod.Config, logger *log.Logger) (*Runner, error) {
	normalized, err := inputmethod.Normalize(cfg)
	if err != nil {
		return nil, err
	}
	if logger == nil {
		logger = log.New(os.Stdout, "[divaslider] ", log.LstdFlags|log.Lmicroseconds)
	}

	manager := state.NewManager()
	r := &Runner{cfg: normalized, logger: logger, manager: manager}
	if err := r.initOutputs(); err != nil {
		return nil, err
	}
	return r, nil
}

func (r *Runner) Start() error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.cancel != nil {
		return nil
	}

	r.ctx, r.cancel = context.WithCancel(context.Background())
	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		r.outputs.Run(r.ctx, r.manager, time.Duration(r.cfg.OutputRefreshMillis)*time.Millisecond)
	}()

	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		clearExpired(r.ctx, r.manager, r.logger, r.cfg.Debug)
	}()

	if r.cfg.Debug {
		r.logger.Printf("debug logging enabled")
		r.wg.Add(1)
		go func() {
			defer r.wg.Done()
			logInputChanges(r.ctx, r.manager, r.logger)
		}()
	}

	if r.cfg.UDPEnabled {
		r.wg.Add(1)
		go func() {
			defer r.wg.Done()
			server := &udp.Server{Addr: r.cfg.UDPAddr, State: r.manager, Logger: r.logger}
			if err := server.ListenAndServe(r.ctx.Done()); err != nil && !errors.Is(err, context.Canceled) {
				r.logger.Printf("udp server stopped: %v", err)
			}
		}()
	}

	if r.cfg.HTTPEnabled {
		server := &http.Server{Addr: r.cfg.HTTPAddr}
		webServer := &web.Server{State: r.manager, Buffer: r.buffer, Logger: r.logger}
		server.Handler = webServer.Handler()
		r.server = server

		r.wg.Add(1)
		go func() {
			defer r.wg.Done()
			r.logger.Printf("http listening on %s", r.cfg.HTTPAddr)
			if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				r.logger.Printf("http server stopped: %v", err)
			}
		}()
	}

	r.logger.Printf("input_method=%s http=%s udp=%s", r.cfg.ID, r.cfg.HTTPAddr, r.cfg.UDPAddr)
	r.logger.Printf("button bits: circle=0x01 cross=0x02 square=0x04 triangle=0x08 start=0x10 test=0x20 service=0x40 coin=0x80 nav=0x100")
	return nil
}

func (r *Runner) Stop() error {
	r.mu.Lock()
	cancel := r.cancel
	server := r.server
	r.cancel = nil
	r.server = nil
	r.mu.Unlock()

	if cancel == nil {
		return nil
	}

	cancel()
	if server != nil {
		_ = server.Close()
	}
	r.wg.Wait()
	return nil
}

func (r *Runner) Close() error {
	_ = r.Stop()
	var firstErr error
	if r.outputs != nil {
		firstErr = r.outputs.Close()
	}
	if r.buffer != nil {
		if err := r.buffer.Close(); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

func (r *Runner) Status() Status {
	r.mu.Lock()
	running := r.cancel != nil
	r.mu.Unlock()

	status := Status{
		Running: running,
		Config:  r.cfg,
		Input:   r.manager.Snapshot(),
		HTTPURL: httpURL(r.cfg.HTTPAddr),
	}
	if r.outputs != nil {
		status.Outputs = r.outputs.Statuses()
	}
	return status
}

func (r *Runner) initOutputs() error {
	var adapters []output.Adapter

	if r.cfg.DLLInjection {
		buffer, err := shm.Open()
		if err != nil {
			return fmt.Errorf("open shared memory %s: %w", shm.Name, err)
		}
		r.buffer = buffer
		r.logger.Printf("shared memory ready: %s (%d bytes)", shm.Name, shm.Size)
		adapters = append(adapters, output.NewSharedMemoryAdapter(buffer))
	}

	if r.cfg.JoystickSlider {
		adapter, err := output.NewSliderAdapter(r.logger)
		if err != nil {
			return err
		}
		adapters = append(adapters, adapter)
	}

	r.outputs = output.NewManager(adapters...)
	return nil
}

func httpURL(addr string) string {
	if addr == "" {
		return ""
	}
	if addr[0] == ':' {
		return "http://127.0.0.1" + addr + "/"
	}
	return "http://" + addr + "/"
}

func clearExpired(ctx context.Context, manager *state.Manager, logger *log.Logger, debug bool) {
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			if manager.ClearIfExpired(now) && debug {
				logger.Printf("input timed out, state cleared")
			}
		}
	}
}

func logInputChanges(ctx context.Context, manager *state.Manager, logger *log.Logger) {
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()

	var last state.Snapshot
	var haveLast bool

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			snap := manager.Snapshot()
			if haveLast && sameInput(last, snap) {
				continue
			}
			haveLast = true
			last = snap

			if !snap.Connected && snap.Buttons == 0 && snap.Coins == 0 && sliderEmpty(snap.Slider) {
				continue
			}

			logger.Printf(
				"input connected=%t buttons=0x%02x coins=%d slider=%s",
				snap.Connected,
				snap.Buttons,
				snap.Coins,
				sliderSummary(snap.Slider))
		}
	}
}

func sameInput(a, b state.Snapshot) bool {
	return a.Connected == b.Connected &&
		a.Buttons == b.Buttons &&
		a.Coins == b.Coins &&
		a.Slider == b.Slider
}

func sliderEmpty(slider [32]byte) bool {
	for _, pressure := range slider {
		if pressure != 0 {
			return false
		}
	}
	return true
}

func sliderSummary(slider [32]byte) string {
	count := 0
	first := -1
	last := -1

	for i, pressure := range slider {
		if pressure == 0 {
			continue
		}
		count++
		if first == -1 {
			first = i
		}
		last = i
	}

	if count == 0 {
		return "none"
	}

	if first == last {
		return fmt.Sprintf("cell=%d", first+1)
	}

	return fmt.Sprintf("count=%d first=%d last=%d", count, first+1, last+1)
}
