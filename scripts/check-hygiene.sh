#!/bin/bash
# Fails when a tracked file contains something that must not be public: credentials, key material, 1Password
# references, absolute home paths of a person. A maintainer can add one extended regular expression per line to the
# untracked file .hygiene-denylist (or the file named by GEKKO_HYGIENE_DENYLIST), e.g. customer names; those are checked
# in the tracked files and in the commit messages. A line that contains "hygiene:allow" is skipped.
# Usage: scripts/check-hygiene.sh [revision]    (with a revision, its whole history is scanned too)
set -euo pipefail
cd "$(dirname "$0")/.."

PATTERNS=(
  'ghp_[A-Za-z0-9]{30,}'
  'gh[ousr]_[A-Za-z0-9]{30,}'
  'github_pat_[A-Za-z0-9_]{30,}'
  'AKIA[0-9A-Z]{16}'
  'AIza[0-9A-Za-z_-]{35}'
  'xox[abprs]-[0-9A-Za-z-]{10,}'
  'sk-[A-Za-z0-9]{32,}'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'op://[A-Za-z0-9_./-]+'
  '(api[_-]?key|secret|passw(or)?d|token)["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"'][A-Za-z0-9+/_=-]{20,}["'"'"']'
  '/Users/[a-z][a-z0-9._-]+/'
)
DENYLIST="${GEKKO_HYGIENE_DENYLIST:-.hygiene-denylist}"
if [ -f "$DENYLIST" ]; then
  while IFS= read -r line; do
    case "$line" in ''|'#'*) ;; *) PATTERNS+=("$line") ;; esac
  done < "$DENYLIST"
fi

status=0
for pattern in "${PATTERNS[@]}"; do
  hits=$(git grep -I -n -i -E -e "$pattern" -- . ':(exclude)scripts/check-hygiene.sh' ':(exclude)*.png' | grep -v 'hygiene:allow' || true)
  if [ -n "$hits" ]; then
    echo "Pattern /$pattern/ matches:"
    echo "$hits" | cut -c1-200
    status=1
  fi
done

if [ "${1:-}" != "" ]; then
  for pattern in "${PATTERNS[@]}"; do
    revisions=$(git rev-list "$1")
    # shellcheck disable=SC2086
    hits=$(git grep -I -l -i -E -e "$pattern" $revisions -- . ':(exclude)scripts/check-hygiene.sh' ':(exclude)*.png' 2>/dev/null | head -5 || true)
    messages=$(git log "$1" --format='%h %s%n%b' | grep -i -E -e "$pattern" | head -5 || true)
    if [ -n "$hits$messages" ]; then
      echo "History of $1 matches /$pattern/:"
      echo "$hits"
      echo "$messages"
      status=1
    fi
  done
fi

if [ "$status" -ne 0 ]; then
  echo "check-hygiene: found content that must not be public (see above)" >&2
  exit 1
fi
echo "check-hygiene: ok"
