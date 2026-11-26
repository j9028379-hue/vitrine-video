#!/usr/bin/env python3
"""Run bounded Gradle checks/build on Kaggle and save auditable result files."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

CMDLINE_TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"


def run_step(name, command, cwd, env, results, timeout=None, shell=False):
    started = time.time()
    log = results / f"{name}.log"
    try:
        with log.open("w") as stream:
            stream.write("$ " + (command if isinstance(command, str) else " ".join(command)) + "\n\n")
            stream.flush()
            completed = subprocess.run(
                command, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT,
                text=True, timeout=timeout, shell=shell,
            )
        return {
            "status": "passed" if completed.returncode == 0 else "failed",
            "passed": completed.returncode == 0,
            "exit_code": completed.returncode,
            "seconds": round(time.time() - started, 2),
            "log": log.name,
        }
    except Exception as exc:
        with log.open("a") as stream:
            stream.write(f"\nWORKER_EXCEPTION: {type(exc).__name__}: {exc}\n")
        return {
            "status": "failed", "passed": False, "exit_code": None,
            "seconds": round(time.time() - started, 2), "log": log.name,
            "error": f"{type(exc).__name__}: {exc}",
        }


def java_major(java, env):
    result = subprocess.run([java, "-version"], env=env, text=True, capture_output=True)
    text = result.stderr + result.stdout
    import re
    match = re.search(r'version "(?:1\.)?(\d+)', text)
    return int(match.group(1)) if match else None


def write_status(path, data):
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-android", choices=("true", "false"), required=True)
    args = parser.parse_args()
    repo = args.repo.resolve()
    results = args.results.resolve()
    results.mkdir(parents=True, exist_ok=True)
    run_android = args.run_android == "true"
    data = {
        "commit": args.commit,
        "repository": "https://github.com/j9028379-hue/vitrine-video",
        "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "success": False,
        "tests": {"status": "not-run", "passed": False},
        "android_build": {"requested": run_android, "status": "not-run", "passed": False},
        "toolchain": {},
        "artifacts": [],
    }
    actual_commit = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    if actual_commit != args.commit:
        data["bootstrap_error"] = f"Checkout mismatch: requested {args.commit}; got {actual_commit}"
        write_status(results / "status.json", data)
        return 0

    sdk = Path("/kaggle/working/android-sdk")
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_SDK_ROOT"] = str(sdk)
    env["GRADLE_USER_HOME"] = "/kaggle/working/gradle-user-home"
    env["CI"] = "true"
    sdkmanager = None
    java = shutil.which("java")
    java_ok = bool(java and java_major(java, env) == 17)

    # Use the image's Java 17 when available; otherwise make one bounded OS package install attempt.
    if not java_ok and os.geteuid() == 0 and shutil.which("apt-get"):
        setup_java = run_step(
            "install-java17", ["bash", "-lc", "apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jdk curl unzip"],
            repo, env, results, timeout=900,
        )
        data["toolchain"]["java_install"] = setup_java
        java = shutil.which("java")
        java_ok = bool(java and java_major(java, env) == 17)

    data["toolchain"]["java"] = {"path": java, "major": java_major(java, env) if java else None, "available_java17": java_ok}
    if java:
        env["JAVA_HOME"] = str(Path(java).resolve().parents[1])
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")

    # Reuse an SDK already present in the image. Install official command-line tools only if needed.
    candidates = [Path(os.environ[p]) for p in ("ANDROID_SDK_ROOT", "ANDROID_HOME") if os.environ.get(p)]
    candidates += [Path("/opt/android-sdk"), Path("/usr/lib/android-sdk"), sdk]
    existing_sdk = next((p for p in candidates if (p / "platforms/android-35").exists()), None)
    sdk = existing_sdk or sdk
    env["ANDROID_HOME"] = str(sdk)
    env["ANDROID_SDK_ROOT"] = str(sdk)
    manager_candidates = [sdk / "cmdline-tools/latest/bin/sdkmanager", sdk / "cmdline-tools/bin/sdkmanager"]
    manager_candidates += list(sdk.glob("cmdline-tools/*/bin/sdkmanager")) if sdk.exists() else []
    sdkmanager = next((p for p in manager_candidates if p.is_file()), None)
    sdk_ready = False

    if java_ok and sdkmanager is None:
        try:
            import urllib.request
            import tempfile
            archive = Path("/kaggle/working/android-commandline-tools.zip")
            urllib.request.urlretrieve(CMDLINE_TOOLS_URL, archive)
            tool_root = sdk / "cmdline-tools"
            extracted = Path("/kaggle/working/android-cmdline-extracted")
            extracted.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(archive) as zf:
                zf.extractall(extracted)
            tool_root.mkdir(parents=True, exist_ok=True)
            latest = tool_root / "latest"
            if latest.exists():
                shutil.rmtree(latest)
            shutil.move(str(extracted / "cmdline-tools"), str(latest))
            sdkmanager = latest / "bin/sdkmanager"
        except Exception as exc:
            data["toolchain"]["sdk_install_error"] = f"{type(exc).__name__}: {exc}"

    if java_ok and sdkmanager and sdkmanager.is_file():
        sdk.parent.mkdir(parents=True, exist_ok=True)
        sdk.mkdir(parents=True, exist_ok=True)
        env["PATH"] = str(sdkmanager.parent) + os.pathsep + env.get("PATH", "")
        licenses = run_step("android-sdk-licenses", "yes | sdkmanager --sdk_root='" + str(sdk) + "' --licenses", repo, env, results, timeout=600, shell=True)
        data["toolchain"]["sdk_licenses"] = licenses
        install = run_step(
            "android-sdk-packages",
            [str(sdkmanager), f"--sdk_root={sdk}", "platform-tools", "platforms;android-35", "build-tools;34.0.0", "build-tools;35.0.0"],
            repo, env, results, timeout=1200,
        )
        data["toolchain"]["sdk_packages"] = install
        sdk_ready = install["passed"] and (sdk / "platforms/android-35").exists()
    data["toolchain"]["android_sdk"] = {"path": str(sdk), "sdkmanager": str(sdkmanager) if sdkmanager else None, "ready": sdk_ready}

    gradle_args = ["./gradlew", "--no-daemon", "--max-workers=1",
                   "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m",
                   "-Pkotlin.compiler.execution.strategy=in-process"]
    tests = run_step(
        "gradle-unit-tests",
        gradle_args + [":vitrine-render-engine:testDebugUnitTest", ":vitrine-video-app:testDebugUnitTest"],
        repo, env, results, timeout=7200,
    )
    data["tests"] = tests

    # Copy Gradle's XML test reports for inspection even if a later task fails.
    for module in ("vitrine-render-engine", "vitrine-video-app"):
        src = repo / module / "build/test-results/testDebugUnitTest"
        dst = results / "test-reports" / module
        if src.exists():
            shutil.copytree(src, dst, dirs_exist_ok=True)

    if run_android:
        if not java_ok or not sdk_ready:
            data["android_build"] = {
                "requested": True, "status": "blocked-toolchain", "passed": False,
                "reason": "Java 17 and Android SDK platform 35/build-tools were not both available; see toolchain logs.",
            }
        else:
            build = run_step("gradle-assemble-release", gradle_args + [":vitrine-video-app:assembleRelease"], repo, env, results, timeout=7200)
            apk_dir = repo / "vitrine-video-app/build/outputs/apk/release"
            apks = list(apk_dir.glob("*.apk")) if apk_dir.exists() else []
            build_data = {"requested": True, **build, "apk_signed": False}
            if build["passed"] and apks:
                dest = results / "apk"
                dest.mkdir(exist_ok=True)
                for apk in apks:
                    copied = dest / apk.name
                    shutil.copy2(apk, copied)
                    build_data["apk"] = copied.relative_to(results).as_posix()
                    build_data["apk_bytes"] = copied.stat().st_size
                    build_data["apk_sha256"] = hashlib.sha256(copied.read_bytes()).hexdigest()
                    try:
                        with zipfile.ZipFile(copied) as zf:
                            bad = zf.testzip()
                        build_data["apk_zip_integrity"] = "passed" if bad is None else f"bad entry: {bad}"
                    except Exception as exc:
                        build_data["apk_zip_integrity"] = f"failed: {type(exc).__name__}: {exc}"
                    apksigner = sdk / "build-tools/35.0.0/apksigner"
                    if apksigner.is_file():
                        verify = run_step("apk-signature-check", [str(apksigner), "verify", "--verbose", str(copied)], repo, env, results, timeout=120)
                        build_data["apk_signed"] = verify["passed"]
                        build_data["signature_check"] = verify
                    break
                build_data["status"] = "passed" if build["passed"] and build_data.get("apk_zip_integrity") == "passed" else "failed"
                build_data["passed"] = build_data["status"] == "passed"
            elif build["passed"]:
                build_data["passed"] = False
                build_data["status"] = "failed"
                build_data["error"] = "Gradle task succeeded but no Release APK was found."
            data["android_build"] = build_data

    data["success"] = bool(data["tests"].get("passed")) and (
        not run_android or bool(data["android_build"].get("passed"))
    )
    data["finished_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    write_status(results / "status.json", data)
    print("Remote status written; tests=" + data["tests"].get("status", "unknown") +
          "; android=" + data["android_build"].get("status", "unknown") +
          "; success=" + str(data["success"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
