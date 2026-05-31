package main

import (
	"context"
	"fmt"
	"log"
	"os/exec"
	"runtime"
	"sync"

	"divaslider-server/pkg/inputmethod"
	"divaslider-server/pkg/runner"
	wailsRuntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

type App struct {
	ctx     context.Context
	mu      sync.Mutex
	current *runner.Runner
	cfg     inputmethod.Config
}

func NewApp() *App {
	return &App{cfg: inputmethod.Default(inputmethod.MethodTLAC)}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
}

func (a *App) shutdown(ctx context.Context) {
	a.mu.Lock()
	current := a.current
	a.current = nil
	a.mu.Unlock()
	if current != nil {
		_ = current.Close()
	}
}

func (a *App) InputMethods() []inputmethod.Info {
	return inputmethod.List()
}

func (a *App) DefaultConfig(id string) inputmethod.Config {
	return inputmethod.Default(id)
}

func (a *App) MethodRequirement(id string) MethodRequirement {
	return checkMethodRequirement(id)
}

func (a *App) Start(cfg inputmethod.Config) (runner.Status, error) {
	normalized, err := inputmethod.Normalize(cfg)
	if err != nil {
		return runner.Status{}, err
	}
	if requirement := checkMethodRequirement(normalized.ID); !requirement.OK {
		return runner.Status{}, fmt.Errorf("%s: %s", requirement.Title, requirement.Action)
	}

	a.mu.Lock()
	previous := a.current
	a.current = nil
	a.mu.Unlock()

	if previous != nil {
		_ = previous.Stop()
		go func() {
			_ = previous.Close()
		}()
	}

	app, err := runner.New(normalized, log.New(logWriter{}, "", 0))
	if err != nil {
		return runner.Status{}, err
	}
	if err := app.Start(); err != nil {
		_ = app.Close()
		return runner.Status{}, err
	}

	a.mu.Lock()
	a.current = app
	a.cfg = normalized
	status := app.Status()
	a.mu.Unlock()

	wailsRuntime.EventsEmit(a.ctx, "server-status", status)
	return status, nil
}

func (a *App) Stop() (runner.Status, error) {
	a.mu.Lock()
	current := a.current
	a.current = nil
	cfg := a.cfg
	a.mu.Unlock()

	if current != nil {
		if err := current.Stop(); err != nil {
			return runner.Status{}, err
		}
		go func() {
			_ = current.Close()
		}()
	}

	status := runner.Status{Running: false, Config: cfg, HTTPURL: localHTTPURL(cfg.HTTPAddr)}
	wailsRuntime.EventsEmit(a.ctx, "server-status", status)
	return status, nil
}

func (a *App) Status() runner.Status {
	a.mu.Lock()
	current := a.current
	cfg := a.cfg
	a.mu.Unlock()

	if current == nil {
		return runner.Status{Running: false, Config: cfg, HTTPURL: localHTTPURL(cfg.HTTPAddr)}
	}
	return current.Status()
}

func (a *App) OpenWebInput() error {
	status := a.Status()
	if status.HTTPURL == "" {
		return nil
	}
	return openURL(status.HTTPURL)
}

type logWriter struct{}

func (logWriter) Write(p []byte) (int, error) {
	return len(p), nil
}

func localHTTPURL(addr string) string {
	if addr == "" {
		return ""
	}
	if addr[0] == ':' {
		return "http://127.0.0.1" + addr + "/"
	}
	return "http://" + addr + "/"
}

func openURL(url string) error {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	return cmd.Start()
}
