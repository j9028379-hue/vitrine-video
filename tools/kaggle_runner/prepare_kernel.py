#!/usr/bin/env python3
"""Create the Kaggle notebook payload for one immutable Git commit."""
import argparse
import json
import re
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-url", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--kaggle-user", required=True)
    parser.add_argument("--run-android", choices=("true", "false"), required=True)
    args = parser.parse_args()

    if not re.fullmatch(r"[0-9a-f]{40}", args.commit):
        raise SystemExit("Expected a full 40-character Git commit SHA")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.kaggle_user):
        raise SystemExit("Kaggle username contains unsupported characters")

    out = Path(".kaggle/remote-kernel")
    out.mkdir(parents=True, exist_ok=True)
    source = r'''import json
import pathlib
import subprocess
import sys

REPO_URL = __REPO_URL__
COMMIT = __COMMIT__
RUN_ANDROID = __RUN_ANDROID__
WORK = pathlib.Path("/kaggle/working")
REPO = WORK / "vitrine-video-source"
RESULTS = WORK / "kaggle-results"
RESULTS.mkdir(parents=True, exist_ok=True)


def save_bootstrap_failure(message):
    (RESULTS / "status.json").write_text(json.dumps({
        "commit": COMMIT,
        "repository": REPO_URL,
        "success": False,
        "tests": {"status": "not-run", "passed": False},
        "android_build": {"requested": RUN_ANDROID, "status": "not-run", "passed": False},
        "bootstrap_error": message,
    }, indent=2) + "\n")

clone = subprocess.run(
    ["git", "init", str(REPO)], text=True, capture_output=True
)
if clone.returncode:
    save_bootstrap_failure("git init failed: " + clone.stderr[-4000:])
else:
    subprocess.run(["git", "-C", str(REPO), "remote", "add", "origin", REPO_URL], check=True)
    fetch = subprocess.run(
        ["git", "-C", str(REPO), "fetch", "--depth", "1", "origin", COMMIT],
        text=True, capture_output=True
    )
    if fetch.returncode:
        save_bootstrap_failure("Could not fetch requested commit: " + fetch.stderr[-4000:])
    else:
        subprocess.run(["git", "-C", str(REPO), "checkout", "--detach", "FETCH_HEAD"], check=True)
        actual = subprocess.check_output(["git", "-C", str(REPO), "rev-parse", "HEAD"], text=True).strip()
        if actual != COMMIT:
            save_bootstrap_failure("Fetched commit SHA does not match requested SHA: " + actual)
        else:
            print("KAGGLE_RECEIVED_COMMIT=" + actual)
            worker = REPO / "tools/kaggle_runner/worker.py"
            run = subprocess.run([
                sys.executable, str(worker),
                "--repo", str(REPO),
                "--results", str(RESULTS),
                "--commit", COMMIT,
                "--run-android", "true" if RUN_ANDROID else "false",
            ], text=True, capture_output=True)
            (RESULTS / "worker.stdout.log").write_text(run.stdout)
            (RESULTS / "worker.stderr.log").write_text(run.stderr)
            if not (RESULTS / "status.json").exists():
                save_bootstrap_failure("Worker exited without status.json; exit=" + str(run.returncode))
            print("REMOTE_WORKER_EXIT=" + str(run.returncode))

print("KAGGLE_RESULTS_DIR=" + str(RESULTS))
print("KAGGLE_STATUS_PRESENT=" + str((RESULTS / "status.json").is_file()))
'''
    source = source.replace("__REPO_URL__", repr(args.repo_url))
    source = source.replace("__COMMIT__", repr(args.commit))
    source = source.replace("__RUN_ANDROID__", "True" if args.run_android == "true" else "False")

    notebook = {
        "cells": [{
            "cell_type": "code",
            "execution_count": None,
            "metadata": {},
            "outputs": [],
            "source": source.splitlines(keepends=True),
        }],
        "metadata": {
            "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
            "language_info": {"name": "python", "version": "3"},
        },
        "nbformat": 4,
        "nbformat_minor": 5,
    }
    (out / "runner.ipynb").write_text(json.dumps(notebook, indent=2) + "\n")
    metadata = {
        "id": f"{args.kaggle_user}/vitrine-video-gradle-ci",
        "title": "Vitrine Video — remote Gradle validation",
        "code_file": "runner.ipynb",
        "language": "python",
        "kernel_type": "notebook",
        "is_private": True,
        "enable_gpu": False,
        "enable_internet": True,
        "dataset_sources": [],
        "competition_sources": [],
        "kernel_sources": [],
    }
    (out / "kernel-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Prepared private Kaggle kernel payload for commit {args.commit}; no Kaggle credential was written into it.")


if __name__ == "__main__":
    main()
