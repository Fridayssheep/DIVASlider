package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"divaslider-server/pkg/inputmethod"
	"divaslider-server/pkg/runner"
)

func main() {
	inputMethod := flag.String("input-method", inputmethod.MethodTLAC, "input method: dll-tlac, joystick-slider")
	httpAddr := flag.String("http", inputmethod.DefaultHTTPAddr, "HTTP/WebSocket listen address")
	udpAddr := flag.String("udp", inputmethod.DefaultUDPAddr, "UDP input listen address")
	disableHTTP := flag.Bool("no-http", false, "disable HTTP/WebSocket debug client")
	disableUDP := flag.Bool("no-udp", false, "disable UDP input")
	debug := flag.Bool("debug", false, "enable verbose input state logging")
	selfTest := flag.Bool("selftest-output", false, "measure output loop timing against ViGEmBus and exit")
	flag.Parse()

	logger := log.New(os.Stdout, "[divaslider] ", log.LstdFlags|log.Lmicroseconds)

	cfg := inputmethod.Default(*inputMethod)
	cfg.HTTPAddr = *httpAddr
	cfg.UDPAddr = *udpAddr
	cfg.HTTPEnabled = !*disableHTTP
	cfg.UDPEnabled = !*disableUDP
	cfg.Debug = *debug

	if *selfTest {
		if err := runSelfTest(time.Duration(cfg.OutputRefreshMillis) * time.Millisecond); err != nil {
			logger.Fatal(err)
		}
		return
	}

	app, err := runner.New(cfg, logger)
	if err != nil {
		logger.Fatal(err)
	}
	defer app.Close()
	if err := app.Start(); err != nil {
		logger.Fatal(err)
	}

	done := make(chan os.Signal, 1)
	signal.Notify(done, os.Interrupt, syscall.SIGTERM)
	<-done
	logger.Printf("stopped")
}
