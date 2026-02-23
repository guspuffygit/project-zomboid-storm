#define WIN32_LEAN_AND_MEAN
#include <windows.h>

HMODULE hOrig = NULL;
static HINSTANCE hThisDLL = NULL;

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    if (fdwReason == DLL_PROCESS_ATTACH) {
        hThisDLL = hinstDLL;
    }
    return TRUE;
}

int  (*pAgent_OnAttach)(void*, char*, void*) = NULL;
int  (*pAgent_OnLoad)(void*, char*, void*)   = NULL;
void (*pAgent_OnUnload)(void*)               = NULL;

void init_instrument_dll() {
    if (hOrig) return;

    SetDllDirectoryA(".\\jre64\\bin");
    hOrig = LoadLibraryA("instrument.dll");
    SetDllDirectoryA(NULL);

    if (!hOrig) {
        WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), "[agentlib] Failed to load instrument.dll\n", 42, NULL, NULL);
        return;
    }

    *(void**)&pAgent_OnAttach = GetProcAddress(hOrig, "Agent_OnAttach");
    *(void**)&pAgent_OnLoad   = GetProcAddress(hOrig, "Agent_OnLoad");
    *(void**)&pAgent_OnUnload = GetProcAddress(hOrig, "Agent_OnUnload");
}

// If jarPath is relative, resolve it against the directory containing this DLL.
// Writes result into buf (size MAX_PATH). Returns buf on resolution, jarPath on failure.
static const char* resolve_jar_path(const char* jarPath, char* buf) {
    /* Absolute if: starts with X:\ (drive) or \\ (UNC) */
    if ((jarPath[0] != '\0' && jarPath[1] == ':') ||
        (jarPath[0] == '\\' && jarPath[1] == '\\')) {
        return jarPath;
    }

    char dllDir[MAX_PATH];
    if (!GetModuleFileNameA(hThisDLL, dllDir, MAX_PATH)) {
        return jarPath;
    }

    // Truncate to directory portion
    char* lastSlash = NULL;
    for (char* p = dllDir; *p; p++) {
        if (*p == '\\' || *p == '/') lastSlash = p;
    }
    if (!lastSlash) return jarPath;
    *(lastSlash + 1) = '\0';

    // Check it fits
    if (lstrlenA(dllDir) + lstrlenA(jarPath) + 1 > MAX_PATH) {
        return jarPath;
    }

    lstrcpyA(buf, dllDir);
    lstrcatA(buf, jarPath);
    return buf;
}

__declspec(dllexport) int Agent_OnLoad(void* jvm, char* tail, void* reserved) {
    char logMsg[1024];
    wsprintf(logMsg, "[agentlib] Agent_OnLoad tail: %s\n", tail ? tail : "NULL");
    WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), logMsg, lstrlenA(logMsg), NULL, NULL);

    if (hOrig == NULL) {
        init_instrument_dll();
    }

    if (!pAgent_OnLoad) {
        return -1;
    }

    const char* jarPath = tail == NULL ? "storm-bootstrap.jar" : tail;

    char resolvedPath[MAX_PATH];
    jarPath = resolve_jar_path(jarPath, resolvedPath);

    wsprintf(logMsg, "[agentlib] Agent_OnLoad resolved jar: %s\n", jarPath);
    WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), logMsg, lstrlenA(logMsg), NULL, NULL);

    return pAgent_OnLoad(jvm, (char*)jarPath, reserved);
}

__declspec(dllexport) int Agent_OnAttach(void* jvm, char* args, void* reserved) {
    char logMsg[1024];
    wsprintf(logMsg, "[agentlib] Agent_OnAttach args: %s\n", args ? args : "NULL");
    WriteConsoleA(GetStdHandle(STD_OUTPUT_HANDLE), logMsg, lstrlenA(logMsg), NULL, NULL);

    if (hOrig == NULL) {
        init_instrument_dll();
    }

    if (!pAgent_OnAttach) {
        return -1;
    }

    const char* jarPath = args == NULL ? "storm-bootstrap.jar" : args;

    char resolvedPath[MAX_PATH];
    jarPath = resolve_jar_path(jarPath, resolvedPath);

    return pAgent_OnAttach(jvm, (char*)jarPath, reserved);
}

__declspec(dllexport) void Agent_OnUnload(void* jvm) {
    if (hOrig == NULL) {
        return;
    }
    if (pAgent_OnUnload) {
        pAgent_OnUnload(jvm);
    }

    FreeLibrary(hOrig);
    hOrig = NULL;
}