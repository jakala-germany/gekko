#!/usr/bin/env python3
"""Checks that the in-app agent and the system runner give the same element the same role.

Usage: python3 -I scripts/check-roles.py <agent tree> <runner tree> [key=Role ...]

The trees are the output of `gekko tree --json` and `gekko tree --json --system` of the same screen. An element is
found in both trees by its id, or by its text when it has no id. Two checks:
  - every key that both trees have as many times as the other has the same roles in both (a key that one of them has
    more often, such as the placeholder text inside of a search field, cannot be told apart and is left out);
  - every `key=Role` that is given: both trees have an element with that key and that role. That is what keeps a screen
    that was not read from passing by comparing nothing, and it names the roles that matter, e.g. `Search=SearchField`.
    `key?=Role` is for what only some iOS versions show (iOS 18 hides a search field until the list is pulled down): when
    neither tree has the key it is fine, when one of them has it both must have it with that role.
"""
import json
import sys
from collections import defaultdict


def load(path):
    with open(path, encoding="utf-8") as file:
        return json.load(file)


def roles_by_key(windows):
    found = defaultdict(list)

    def visit(node):
        key = node.get("id") or node.get("text")
        if key:
            found[key].append(node.get("role"))
        for child in node.get("children", []):
            visit(child)

    for window in windows:
        visit(window)
    return found


def main(argv):
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    agent = roles_by_key(load(argv[1]))
    runner = roles_by_key(load(argv[2]))
    expected = []
    for item in argv[3:]:
        key, role = item.rsplit("=", 1)
        expected.append((key[:-1] if key.endswith("?") else key, role, key.endswith("?")))

    compared = [key for key in sorted(agent.keys() & runner.keys()) if len(agent[key]) == len(runner[key])]
    failures = []
    for key in compared:
        if sorted(agent[key]) != sorted(runner[key]):
            failures.append(f"roles of {key}: agent {agent[key]}, runner {runner[key]}")
    for key, role, optional in expected:
        if optional and key not in agent and key not in runner:
            continue
        for name, roles in (("agent", agent), ("runner", runner)):
            if role not in roles.get(key, []):
                failures.append(f"{key} is no {role} in the {name} tree, it has {roles.get(key)}")

    for failure in failures:
        print(failure, file=sys.stderr)
    if failures:
        return 1
    print(f"roles match: {len(compared)} keys compared, {len(expected)} expected ({argv[1]}, {argv[2]})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
