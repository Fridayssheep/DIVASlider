//go:build windows

package vigem

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"unsafe"
)

const (
	vigemSuccess                 uintptr = 0x20000000
	vigemErrorBusNotFound        uintptr = 0xe0000001
	vigemErrorNoFreeSlot         uintptr = 0xe0000002
	vigemErrorInvalidTarget      uintptr = 0xe0000003
	vigemErrorRemovalFailed      uintptr = 0xe0000004
	vigemErrorAlreadyConnected   uintptr = 0xe0000005
	vigemErrorTargetUninit       uintptr = 0xe0000006
	vigemErrorTargetNotPluggedIn uintptr = 0xe0000007
	vigemErrorVersionMismatch    uintptr = 0xe0000008
	vigemErrorBusAccessFailed    uintptr = 0xe0000009
	vigemErrorInvalidHandle      uintptr = 0xe0000013
	vigemErrorInvalidParameter   uintptr = 0xe0000015
	vigemErrorNotSupported       uintptr = 0xe0000016
	vigemErrorWinAPI             uintptr = 0xe0000017
	vigemErrorTimedOut           uintptr = 0xe0000018
)

type Client struct {
	mu         sync.Mutex
	dll        *syscall.LazyDLL
	client     uintptr
	target     uintptr
	connected  bool
	targetLive bool

	alloc        *syscall.LazyProc
	free         *syscall.LazyProc
	connect      *syscall.LazyProc
	disconnect   *syscall.LazyProc
	targetAlloc  *syscall.LazyProc
	targetFree   *syscall.LazyProc
	targetAdd    *syscall.LazyProc
	targetRemove *syscall.LazyProc
	ds4Update    *syscall.LazyProc
}

func Open() (*Client, error) {
	c := &Client{}
	c.dll = syscall.NewLazyDLL(dllPath())
	c.alloc = c.dll.NewProc("vigem_alloc")
	c.free = c.dll.NewProc("vigem_free")
	c.connect = c.dll.NewProc("vigem_connect")
	c.disconnect = c.dll.NewProc("vigem_disconnect")
	c.targetAlloc = c.dll.NewProc("vigem_target_ds4_alloc")
	c.targetFree = c.dll.NewProc("vigem_target_free")
	c.targetAdd = c.dll.NewProc("vigem_target_add")
	c.targetRemove = c.dll.NewProc("vigem_target_remove")
	c.ds4Update = c.dll.NewProc("vigem_target_ds4_update")

	if err := c.dll.Load(); err != nil {
		return nil, fmt.Errorf("load ViGEmClient.dll: %w", err)
	}
	if err := c.findExports(); err != nil {
		return nil, fmt.Errorf("find ViGEmClient exports: %w", err)
	}

	client, _, _ := c.alloc.Call()
	if client == 0 {
		return nil, errors.New("vigem_alloc returned null")
	}
	c.client = client

	if err := vigemError(c.connect.Call(c.client)); err != nil {
		c.free.Call(c.client)
		return nil, fmt.Errorf("vigem_connect: %w", err)
	}
	c.connected = true

	target, _, _ := c.targetAlloc.Call()
	if target == 0 {
		c.Close()
		return nil, errors.New("vigem_target_ds4_alloc returned null")
	}
	c.target = target

	if err := vigemError(c.targetAdd.Call(c.client, c.target)); err != nil {
		c.Close()
		return nil, fmt.Errorf("vigem_target_add DS4: %w", err)
	}
	c.targetLive = true
	return c, nil
}

func (c *Client) findExports() error {
	procs := []*syscall.LazyProc{
		c.alloc,
		c.free,
		c.connect,
		c.disconnect,
		c.targetAlloc,
		c.targetFree,
		c.targetAdd,
		c.targetRemove,
		c.ds4Update,
	}
	for _, proc := range procs {
		if err := proc.Find(); err != nil {
			return err
		}
	}
	return nil
}

func dllPath() string {
	executable, _ := os.Executable()
	executableDir := filepath.Dir(executable)
	candidates := []string{
		"ViGEmClient.dll",
		filepath.Join(executableDir, "ViGEmClient.dll"),
	}
	if wd, err := os.Getwd(); err == nil {
		candidates = append(candidates, projectDLLCandidates(wd)...)
	}
	candidates = append(candidates, projectDLLCandidates(executableDir)...)
	for _, candidate := range candidates {
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
	}
	return "ViGEmClient.dll"
}

func projectDLLCandidates(start string) []string {
	var candidates []string
	dir := filepath.Clean(start)
	for {
		candidates = append(candidates,
			filepath.Join(dir, "ViGEmClient.dll"),
			filepath.Join(dir, "Resource", "ViGEmBus", "sdk", "bin", "release", "x64", "ViGEmClient.dll"),
		)
		parent := filepath.Dir(dir)
		if parent == dir {
			return candidates
		}
		dir = parent
	}
}

func (c *Client) Update(report DS4Report) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.client == 0 || c.target == 0 || !c.targetLive {
		return errors.New("DS4 target is not connected")
	}

	raw := report.Bytes()

	// ViGEm's C API takes DS4_REPORT by value. On Windows x64, this 10-byte
	// struct is passed by reference according to the Microsoft x64 ABI.
	return vigemError(c.ds4Update.Call(c.client, c.target, uintptr(unsafe.Pointer(&raw[0]))))
}

func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.targetLive && c.client != 0 && c.target != 0 {
		c.targetRemove.Call(c.client, c.target)
		c.targetLive = false
	}
	if c.target != 0 {
		c.targetFree.Call(c.target)
		c.target = 0
	}
	if c.connected && c.client != 0 {
		c.disconnect.Call(c.client)
		c.connected = false
	}
	if c.client != 0 {
		c.free.Call(c.client)
		c.client = 0
	}
	return nil
}

func vigemError(ret uintptr, _ uintptr, _ error) error {
	if ret == vigemSuccess {
		return nil
	}
	return fmt.Errorf("%s (0x%08x)", vigemMessage(ret), uint32(ret))
}

func vigemMessage(ret uintptr) string {
	switch ret {
	case vigemErrorBusNotFound:
		return "ViGEmBus driver is not installed or not running"
	case vigemErrorNoFreeSlot:
		return "ViGEmBus has no free virtual controller slots"
	case vigemErrorInvalidTarget:
		return "ViGEmBus rejected the virtual DS4 target"
	case vigemErrorRemovalFailed:
		return "ViGEmBus failed to remove the virtual controller"
	case vigemErrorAlreadyConnected:
		return "ViGEmBus target is already connected"
	case vigemErrorTargetUninit:
		return "ViGEmBus target is not initialized"
	case vigemErrorTargetNotPluggedIn:
		return "ViGEmBus target is not plugged in"
	case vigemErrorVersionMismatch:
		return "ViGEmBus driver version is incompatible with ViGEmClient.dll"
	case vigemErrorBusAccessFailed:
		return "ViGEmBus driver was found but could not be opened"
	case vigemErrorInvalidHandle:
		return "ViGEmBus connection handle is invalid"
	case vigemErrorInvalidParameter:
		return "ViGEmBus received an invalid parameter"
	case vigemErrorNotSupported:
		return "ViGEmBus driver does not support this operation"
	case vigemErrorWinAPI:
		return "ViGEmBus hit an unexpected Windows API error"
	case vigemErrorTimedOut:
		return "ViGEmBus operation timed out"
	default:
		return "ViGEmBus returned an unknown error"
	}
}
