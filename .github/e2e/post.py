#!/usr/bin/env python3
"""
Temporary diagnostic helper (NOT part of the product).

Ships text files back to the developer through channels readable via api.github.com:
  1. one completed check run per <=55k chunk (needs `checks: write`)  -> output.text
  2. (always) workflow-command annotations for the small priority file(s), as a fallback

usage: post.py <prefix> <priority-summary-file|-> <file> [<file> ...]
"""
import json
import os
import subprocess
import sys

LIMIT = 55000
ANN_CHUNK = 3000


def esc(s):
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main():
    prefix, summary = sys.argv[1], sys.argv[2]
    files = sys.argv[3:]
    repo = os.environ["GITHUB_REPOSITORY"]
    sha = os.environ["GITHUB_SHA"]

    # --- fallback channel: annotations (always works, small) -----------------
    if summary != "-" and os.path.exists(summary):
        text = open(summary, errors="replace").read()
        chunks = [text[i:i + ANN_CHUNK] for i in range(0, len(text), ANN_CHUNK)][:9]
        for n, c in enumerate(chunks, 1):
            print("::notice title=diag %s summary %d/%d::%s" % (prefix, n, len(chunks), esc(c)), flush=True)

    # --- main channel: check runs --------------------------------------------
    for p in files:
        try:
            text = open(p, errors="replace").read()
        except Exception as e:  # noqa
            text = "(cannot read %s: %s)" % (p, e)
        base = os.path.basename(p)
        chunks = [text[i:i + LIMIT] for i in range(0, max(len(text), 1), LIMIT)] or [""]
        for n, c in enumerate(chunks, 1):
            name = "diag:%s:%s" % (prefix, base) + (" [%d/%d]" % (n, len(chunks)) if len(chunks) > 1 else "")
            payload = {
                "name": name[:250],
                "head_sha": sha,
                "status": "completed",
                "conclusion": "neutral",
                "output": {
                    "title": name[:250],
                    "summary": "%s / %s (%d chars)" % (prefix, base, len(text)),
                    "text": "```\n" + c.replace("```", "'''") + "\n```",
                },
            }
            r = subprocess.run(
                ["gh", "api", "-X", "POST", "repos/%s/check-runs" % repo, "--input", "-"],
                input=json.dumps(payload).encode(),
                capture_output=True,
            )
            print("%s rc=%d %s" % (name, r.returncode, r.stderr.decode()[:300]), flush=True)


if __name__ == "__main__":
    main()
