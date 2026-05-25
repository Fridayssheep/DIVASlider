#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "divaslider_log.h"

static const wchar_t divaslider_log_path[] = L"divaslider-dll.log";

void divaslider_logf(const char *fmt, ...)
{
    char message[1024];
    char line[1200];
    DWORD written;
    HANDLE file;
    SYSTEMTIME now;
    va_list ap;
    int len;

    if (fmt == NULL) {
        return;
    }

    va_start(ap, fmt);
    vsnprintf(message, sizeof(message), fmt, ap);
    va_end(ap);

    message[sizeof(message) - 1] = '\0';

    GetLocalTime(&now);
    len = snprintf(
            line,
            sizeof(line),
            "%04u-%02u-%02u %02u:%02u:%02u.%03u %s\r\n",
            now.wYear,
            now.wMonth,
            now.wDay,
            now.wHour,
            now.wMinute,
            now.wSecond,
            now.wMilliseconds,
            message);

    if (len <= 0) {
        return;
    }

    line[sizeof(line) - 1] = '\0';
    OutputDebugStringA(line);

    file = CreateFileW(
            divaslider_log_path,
            FILE_APPEND_DATA,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            NULL,
            OPEN_ALWAYS,
            FILE_ATTRIBUTE_NORMAL,
            NULL);

    if (file == INVALID_HANDLE_VALUE) {
        return;
    }

    WriteFile(file, line, (DWORD) strlen(line), &written, NULL);
    CloseHandle(file);
}

