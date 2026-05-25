//go:build windows

package shm

import (
	"fmt"
	"syscall"
	"unsafe"
)

const (
	pageReadWrite    = 0x04
	fileMapAllAccess = 0xF001F
)

var (
	kernel32              = syscall.NewLazyDLL("kernel32.dll")
	procCreateFileMapping = kernel32.NewProc("CreateFileMappingW")
	procMapViewOfFile     = kernel32.NewProc("MapViewOfFile")
	procUnmapViewOfFile   = kernel32.NewProc("UnmapViewOfFile")
	procCloseHandle       = kernel32.NewProc("CloseHandle")
)

func Open() (*Buffer, error) {
	name, err := syscall.UTF16PtrFromString(Name)
	if err != nil {
		return nil, err
	}

	handle, _, callErr := procCreateFileMapping.Call(
		^uintptr(0),
		0,
		pageReadWrite,
		0,
		Size,
		uintptr(unsafe.Pointer(name)),
	)
	if handle == 0 {
		return nil, fmt.Errorf("CreateFileMappingW: %w", callErr)
	}

	view, _, callErr := procMapViewOfFile.Call(handle, fileMapAllAccess, 0, 0, Size)
	if view == 0 {
		procCloseHandle.Call(handle)
		return nil, fmt.Errorf("MapViewOfFile: %w", callErr)
	}

	data := unsafe.Slice((*byte)(unsafe.Pointer(view)), Size)
	return &Buffer{
		data: data,
		done: func() error {
			procUnmapViewOfFile.Call(view)
			procCloseHandle.Call(handle)
			return nil
		},
	}, nil
}
