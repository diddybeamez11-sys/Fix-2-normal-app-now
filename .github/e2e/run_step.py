#!/usr/bin/env python3
"""
Temporary diagnostic helper (NOT part of the product).

Runs the `run:` steps of one job of a workflow file, from step START (inclusive) up to step END
(exclusive), so the diagnostic build executes exactly the same commands as the real CI build.

usage: run_step.py <workflow.yml> <job> <START step name> <END step name | "">
"""
import subprocess
import sys

import yaml


def main():
    path, job, start, end = sys.argv[1:5]
    steps = yaml.safe_load(open(path))["jobs"][job]["steps"]
    names = [s.get("name") for s in steps]
    i = names.index(start)
    j = names.index(end) if end else len(steps)
    for s in steps[i:j]:
        if "run" not in s:
            continue
        print("::group::" + str(s.get("name")), flush=True)
        r = subprocess.run(
            ["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", s["run"]],
            cwd=s.get("working-directory", "."),
        )
        print("::endgroup::", flush=True)
        if r.returncode:
            print("step failed: %s (exit %d)" % (s.get("name"), r.returncode), flush=True)
            sys.exit(r.returncode)


if __name__ == "__main__":
    main()
