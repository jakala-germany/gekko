#!/bin/bash
# Checks what the sample tests with the tag `exactness` logged (`copyText` prints `name = "value"`): how far the sample
# list was scrolled by a drag and by a swipe, through the in-app agent and through the system runner.
#   - drag: after a drag of 10 percent of the screen and one of 20 percent more, the list is scrolled 3 times as far as
#     after the first. A finger that loses the touch slop (about 8 dp on Android, 12 pt on iOS) every time ends short.
#   - swipe: a fast swipe flings the list on, so it moves it at least 1.5 times as far as a slow swipe of the same
#     length (on the emulator: 2.3 to 2.7 times on Android, much more on iOS, where it reaches the end of the list).
# Usage: scripts/check-touches.sh <log of a gekko run of examples/sample-tests> [--no-runner-drag]
# --no-runner-drag leaves out the drag of the system runner: on iOS a long drag of the runner can fling the content on
# (the agent, which scrolling uses, is exact).
set -euo pipefail

LOG="${1:?usage: scripts/check-touches.sh <log of a gekko run of examples/sample-tests> [--no-runner-drag]}"
RUNNER_DRAG=yes
if [ "${2:-}" = "--no-runner-drag" ]; then
  RUNNER_DRAG=no
fi
TOLERANCE=4

value() {
  local found
  found="$(sed -n "s/^ *$1 = \"\([0-9][0-9]*\)\"\$/\1/p" "$LOG" | head -n 1)"
  if [ -z "$found" ]; then
    echo "check-touches: the tests did not log $1" >&2
    exit 1
  fi
  echo "$found"
}

for kind in agent runner; do
  if [ "$kind" = runner ] && [ "$RUNNER_DRAG" = no ]; then
    echo "check-touches: runner drag left out"
    continue
  fi
  ten="$(value "${kind}Ten")"
  thirty="$(value "${kind}Thirty")"
  off=$((thirty - 3 * ten))
  off=${off#-}
  echo "check-touches: $kind drag: 10% moved the list $ten, 20% more: $thirty in all, off by $off from 3 x $ten"
  if [ "$ten" -le 0 ] || [ "$off" -gt "$TOLERANCE" ]; then
    echo "check-touches: a $kind drag does not move the list exactly as far as the finger (allowed: $TOLERANCE)" >&2
    exit 1
  fi
done

for kind in agent runner; do
  slow="$(value "${kind}Slow")"
  fast_in_all="$(value "${kind}Fast")"
  fast=$((fast_in_all - slow))
  echo "check-touches: $kind swipe: 1000 ms moved the list $slow, 100 ms moved it $fast"
  if [ "$slow" -le 0 ] || [ $((2 * fast)) -lt $((3 * slow)) ]; then
    echo "check-touches: a fast $kind swipe must fling the list at least 1.5 times as far as a slow one" >&2
    exit 1
  fi
done
echo "check-touches: ok"
