#!/usr/bin/env python3
"""
setup.py — entry point for the Ratpack Panel setup wizard.

  python3 setup.py            interactive
  python3 setup.py --help
"""

import sys

from ratpack.setup_wizard import main

if __name__ == "__main__":
    sys.exit(main() or 0)
