#!/bin/bash
#
# Dhan Algo Desk launcher.
#
# Double-click this file in Finder to run one full cycle of the Skew Hunter
# pipeline (collect -> paper trade -> chart) and open the resulting chart.
#
# If Finder refuses to open it, the execute bit was stripped in transit. Fix:
#   chmod +x "Start Dhan Algo Desk.command"
#   xattr -d com.apple.quarantine "Start Dhan Algo Desk.command" 2>/dev/null

set -uo pipefail

# Run from the directory this script lives in, so it works wherever it is copied
# as long as the repo sits alongside it.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Locate the repo: either this script is inside it, or it is at one of the
# usual spots. Override by exporting SKEWHUNTER_DIR before running.
REPO_DIR="${SKEWHUNTER_DIR:-}"
if [ -z "$REPO_DIR" ]; then
    for candidate in "$SCRIPT_DIR" "$HOME/skewhunter-iv" "$HOME/Desktop/skewhunter-iv" "$HOME/Documents/skewhunter-iv"; do
        if [ -f "$candidate/collector.py" ]; then
            REPO_DIR="$candidate"
            break
        fi
    done
fi

if [ -z "$REPO_DIR" ]; then
    echo "ERROR: could not find the skewhunter-iv checkout."
    echo "Set it explicitly, e.g.:"
    echo "  export SKEWHUNTER_DIR=/path/to/skewhunter-iv"
    echo
    read -n 1 -s -r -p "Press any key to close..."
    exit 1
fi

cd "$REPO_DIR" || exit 1
echo "Dhan Algo Desk"
echo "Working directory: $REPO_DIR"
echo

PYTHON="$(command -v python3 || true)"
if [ -z "$PYTHON" ]; then
    echo "ERROR: python3 not found. Install it with:  brew install python"
    echo
    read -n 1 -s -r -p "Press any key to close..."
    exit 1
fi
echo "Python: $PYTHON ($("$PYTHON" --version 2>&1))"

# Install dependencies only when something is actually missing, so the common
# case stays fast.
if ! "$PYTHON" -c "import requests, pandas, plotly, pytz" >/dev/null 2>&1; then
    echo "Installing missing Python packages..."
    "$PYTHON" -m pip install --quiet --user requests pandas plotly pytz || {
        echo "ERROR: dependency install failed."
        echo
        read -n 1 -s -r -p "Press any key to close..."
        exit 1
    }
fi

run_step() {
    local label="$1" script="$2"
    echo
    echo "==> $label"
    if ! "$PYTHON" "$script"; then
        echo "ERROR: $script failed."
        echo
        read -n 1 -s -r -p "Press any key to close..."
        exit 1
    fi
}

run_step "Collecting IV data"   collector.py
run_step "Running paper trader" paper_trade.py
run_step "Building chart"       chart.py

echo
if [ -f index.html ]; then
    echo "Opening chart..."
    open index.html
else
    echo "WARNING: index.html was not produced."
fi

echo
echo "Done."
read -n 1 -s -r -p "Press any key to close..."
