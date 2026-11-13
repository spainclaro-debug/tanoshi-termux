#!/usr/bin/env python3
"""Replace the `secret` entry in a Tanoshi config.yml.

Tanoshi encrypts image URLs with AES-128-CBC, so the secret must be exactly
16 bytes. Run as: repair_secret.py <config.yml> <new-secret>
"""
import re
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: repair_secret.py <config.yml> <new-secret>", file=sys.stderr)
        return 2
    path, new = sys.argv[1], sys.argv[2]
    if len(new) != 16:
        print("new secret must be exactly 16 characters", file=sys.stderr)
        return 2

    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    pattern = re.compile(r"^(['\"]?)secret(['\"]?)[ \t]*:.*$", re.MULTILINE)

    def repl(match: "re.Match[str]") -> str:
        # Keep the key exactly as written; only quote the value if it was or must be.
        key = match.group(1) + "secret" + match.group(2)
        quote = match.group(1) or match.group(2) or '"'
        return f"{key}: {quote}{new}{quote}"

    updated, count = pattern.subn(repl, text, count=1)
    if count == 0:
        updated = text.rstrip("\n") + f'\nsecret: "{new}"\n'

    with open(path, "w", encoding="utf-8") as handle:
        handle.write(updated)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
