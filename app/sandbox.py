"""Run a child command without IP sockets; works without Docker privileges."""
from __future__ import annotations

import ctypes
import ctypes.util
import errno
import os
import shutil
import socket
import sys


class ArgCompare(ctypes.Structure):
    _fields_ = [("arg", ctypes.c_uint), ("op", ctypes.c_int),
                ("datum_a", ctypes.c_uint64), ("datum_b", ctypes.c_uint64)]


def restrict_network() -> None:
    """Deny non-Unix socket creation, and fail closed if seccomp is unavailable."""
    name = ctypes.util.find_library("seccomp")
    if sys.platform != "linux" or not name:
        raise RuntimeError("Linux libseccomp2 is required for offline child processes")
    library = ctypes.CDLL(name, use_errno=True)
    library.seccomp_init.argtypes = [ctypes.c_uint32]
    library.seccomp_init.restype = ctypes.c_void_p
    library.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    library.seccomp_syscall_resolve_name.restype = ctypes.c_int
    library.seccomp_rule_add_array.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int,
                                              ctypes.c_uint, ctypes.POINTER(ArgCompare)]
    library.seccomp_rule_add_array.restype = ctypes.c_int
    library.seccomp_load.argtypes = [ctypes.c_void_p]
    library.seccomp_load.restype = ctypes.c_int
    library.seccomp_release.argtypes = [ctypes.c_void_p]
    library.seccomp_release.restype = None
    context = library.seccomp_init(0x7FFF0000)  # SCMP_ACT_ALLOW
    if not context:
        raise RuntimeError("Unable to allocate the network filter")
    try:
        for name_bytes in (b"socket", b"socketpair"):
            syscall = library.seccomp_syscall_resolve_name(name_bytes)
            # SCMP_CMP_NE: allow AF_UNIX, deny all other socket domains.
            comparison = ArgCompare(0, 1, socket.AF_UNIX, 0)
            if syscall < 0 or library.seccomp_rule_add_array(
                    context, 0x00050000 | errno.EPERM, syscall, 1, ctypes.byref(comparison)) != 0:
                raise RuntimeError("Unable to configure the network filter")
        # libseccomp sets NO_NEW_PRIVS before loading; no extra capabilities needed.
        if library.seccomp_load(context) != 0:
            raise RuntimeError("Unable to activate the network filter")
    finally:
        library.seccomp_release(context)


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: sandbox.py <command> [args...]", file=sys.stderr)
        return 2
    try:
        executable = shutil.which(sys.argv[1])
        if executable is None:
            raise RuntimeError("Child executable is not installed")
        restrict_network()
        os.execve(executable, [executable, *sys.argv[2:]], os.environ)
    except (OSError, RuntimeError) as error:
        print(f"Offline process refused: {error}", file=sys.stderr)
        return 126


if __name__ == "__main__":
    raise SystemExit(main())
