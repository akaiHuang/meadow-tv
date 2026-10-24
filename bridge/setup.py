#!/usr/bin/env python3
"""Compatibility entry point: explicit setup now lives in tools/companion.py."""
import runpy
import sys
from pathlib import Path

if __name__ == "__main__":
    sys.argv = [str(Path(__file__).resolve().parents[1] / "tools/companion.py"), "setup", *sys.argv[1:]]
    runpy.run_path(sys.argv[0], run_name="__main__")
