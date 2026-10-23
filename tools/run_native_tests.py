"""Compile and run the native host tests (tests/native) with a C++17 compiler already on this machine.

Uses $CXX, or c++/clang++/g++ from PATH; on Windows falls back to the installed MSVC (found with vswhere).
Nothing is installed or downloaded. No device, Android SDK or RayNeo SDK is needed.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [ROOT / 'tests/native/test_main.cpp']
INCLUDES = [ROOT / 'tests/native', ROOT / 'tests/native/host_stub', ROOT / 'xrspatial/src/main/cpp', ROOT / 'third_party/openxr/include']
DEFINES = ['XR_USE_PLATFORM_ANDROID', 'XR_NO_PROTOTYPES']


def build_unix(compiler: str, out: Path) -> list[str]:
    return [compiler, '-std=c++17', '-Wall', '-Wextra', '-Werror', '-O1', *[f'-D{d}' for d in DEFINES],
            *[f'-I{i}' for i in INCLUDES], '-o', str(out), *map(str, SOURCES)]


def build_msvc(build: Path, out: Path) -> Path:
    vswhere = Path(os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)')) / 'Microsoft Visual Studio/Installer/vswhere.exe'
    if not vswhere.is_file():
        raise SystemExit('No C++ compiler found: set CXX, or install a C++17 compiler (not done automatically)')
    vs = subprocess.check_output([str(vswhere), '-latest', '-products', '*', '-requires',
                                  'Microsoft.VisualStudio.Component.VC.Tools.x86.x64', '-property', 'installationPath'],
                                 text=True, timeout=30).strip()
    setup = Path(vs) / 'VC/Auxiliary/Build/vcvars64.bat'
    if not setup.is_file():
        raise SystemExit('MSVC x64 tools not found')
    command = ['cl', '/nologo', '/EHsc', '/std:c++17', '/W4', '/WX', '/utf-8', '/D_CRT_SECURE_NO_WARNINGS',
               *[f'/D{d}' for d in DEFINES], *[f'/I{i}' for i in INCLUDES], f'/Fe:{out}', *map(str, SOURCES)]
    batch = build / 'compile.cmd'
    batch.write_text(f'@echo off\ncall "{setup}" >nul\nif errorlevel 1 exit /b 1\ncd /d "{build}"\n'
                     + subprocess.list2cmdline(command) + '\nexit /b %errorlevel%\n', encoding='utf-8')
    return batch


def main() -> int:
    build = ROOT / 'build/native-tests'
    build.mkdir(parents=True, exist_ok=True)
    out = build / ('native_tests.exe' if os.name == 'nt' else 'native_tests')
    compiler = os.environ.get('CXX') or next((c for c in ('c++', 'clang++', 'g++') if shutil.which(c)), None)
    if compiler:
        compiled = subprocess.run(build_unix(compiler, out), capture_output=True, text=True, timeout=300)
    elif os.name == 'nt':
        compiled = subprocess.run(['cmd.exe', '/d', '/c', str(build_msvc(build, out))], cwd=build,
                                  capture_output=True, text=True, errors='replace', timeout=300)
    else:
        raise SystemExit('No C++ compiler found: set CXX')
    sys.stdout.write(compiled.stdout + compiled.stderr)
    if compiled.returncode != 0:
        return compiled.returncode
    tested = subprocess.run([str(out)], capture_output=True, text=True, timeout=120)
    sys.stdout.write(tested.stdout + tested.stderr)
    return tested.returncode


if __name__ == '__main__':
    raise SystemExit(main())
