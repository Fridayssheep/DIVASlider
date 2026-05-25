package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"divaslider-server/internal/shm"
	"divaslider-server/internal/state"
	"divaslider-server/internal/udp"
	"divaslider-server/internal/web"
)

func main() {
	httpAddr := flag.String("http", ":52469", "HTTP/WebSocket listen address")
	udpAddr := flag.String("udp", ":52468", "UDP input listen address")
	disableHTTP := flag.Bool("no-http", false, "disable HTTP/WebSocket debug client")
	disableUDP := flag.Bool("no-udp", false, "disable UDP input")
	debug := flag.Bool("debug", false, "enable verbose input state logging")
	flag.Parse()

	logger := log.New(os.Stdout, "[divaslider] ", log.LstdFlags|log.Lmicroseconds)
	manager := state.NewManager()

	buffer, err := shm.Open()
	if err != nil {
		logger.Fatalf("open shared memory %s: %v", shm.Name, err)
	}
	defer buffer.Close()
	logger.Printf("shared memory ready: %s (%d bytes)", shm.Name, shm.Size)

	stop := make(chan struct{})
	done := make(chan os.Signal, 1)
	signal.Notify(done, os.Interrupt, syscall.SIGTERM)

	go writeSharedMemory(stop, buffer, manager)
	go clearExpired(stop, manager, logger, *debug)
	if *debug {
		logger.Printf("debug logging enabled")
		go logInputChanges(stop, manager, logger)
	}

	if !*disableUDP {
		go func() {
			server := &udp.Server{Addr: *udpAddr, State: manager, Logger: logger}
			if err := server.ListenAndServe(stop); err != nil {
				logger.Printf("udp server stopped: %v", err)
			}
		}()
	}

	if !*disableHTTP {
		go func() {
			server := &web.Server{Addr: *httpAddr, State: manager, Buffer: buffer, Logger: logger}
			if err := server.ListenAndServe(); err != nil {
				logger.Printf("http server stopped: %v", err)
			}
		}()
	}

	logger.Printf("button bits: circle=0x01 cross=0x02 square=0x04 triangle=0x08 start=0x10 test=0x20 service=0x40 coin=0x80")
	<-done
	close(stop)
	logger.Printf("stopped")
}

func writeSharedMemory(stop <-chan struct{}, buffer *shm.Buffer, manager *state.Manager) {
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			buffer.WriteSnapshot(manager.SnapshotForWrite())
		}
	}
}

func clearExpired(stop <-chan struct{}, manager *state.Manager, logger *log.Logger, debug bool) {
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-stop:
			return
		case now := <-ticker.C:
			if manager.ClearIfExpired(now) && debug {
				logger.Printf("input timed out, state cleared")
			}
		}
	}
}

func logInputChanges(stop <-chan struct{}, manager *state.Manager, logger *log.Logger) {
	ticker := time.NewTicker(50 * time.Millisecond)
	defer ticker.Stop()

	var last state.Snapshot
	var haveLast bool

	for {
		select {
		case <-stop:
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
		return "cell=" + strconv.Itoa(first+1)
	}

	return "count=" + strconv.Itoa(count) + " first=" + strconv.Itoa(first+1) + " last=" + strconv.Itoa(last+1)
}
