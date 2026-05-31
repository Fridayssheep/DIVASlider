package runner

import (
	"net/http"
	"testing"
	"time"

	"divaslider-server/pkg/inputmethod"
)

func TestStopReturnsPromptlyWithHTTPEnabled(t *testing.T) {
	cfg := inputmethod.Default(inputmethod.MethodTLAC)
	cfg.HTTPAddr = "127.0.0.1:0"
	cfg.UDPEnabled = false

	app, err := New(cfg, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()

	if err := app.Start(); err != nil {
		t.Fatal(err)
	}

	done := make(chan error, 1)
	go func() {
		done <- app.Stop()
	}()

	select {
	case err := <-done:
		if err != nil && err != http.ErrServerClosed {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Stop did not return promptly")
	}
}
