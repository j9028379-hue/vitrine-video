#!/usr/bin/env python3
"""Audit the final Android APK for T3 delivery assets and excluded demo media."""
import hashlib
import sys
import zipfile
from pathlib import Path

EXPECTED_TEMPLATE = "assets/packages/RESTAURATION-T3-1.0.0.zip"
EXPECTED_LIBRARY = "assets/music/RESTAURATION-T3/1.0.0/library.json"
EXPECTED_VAULTS = {
    f"assets/music/RESTAURATION-T3/1.0.0/{n:02d}.mp3.vault" for n in range(1, 5)
}
FORBIDDEN_EXTENSIONS = (".wav", ".mp4", ".mov", ".avi", ".mkv")
FORBIDDEN_PATH_PARTS = (
    "/fixtures/", "/test-fixtures/", "/golden/", "/demo/", "/demos/",
    "/screenshots/", "/proofs/", "/reports/", "/rushes/", "/simulation/",
)
REPORT = Path("ci-artifacts/RESTAURATION_T3_APK_AUDIT.txt")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_restauration_t3_apk.py <release.apk>")
    apk = Path(sys.argv[1])
    if not apk.is_file() or apk.stat().st_size == 0:
        raise SystemExit("release APK is missing or empty")
    checks = []
    def check(label: str, valid: bool) -> None:
        checks.append((label, bool(valid)))
        if not valid:
            raise SystemExit(f"FAIL {label}")

    with zipfile.ZipFile(apk) as bundle:
        names = bundle.namelist()
        check("APK ZIP integrity", bundle.testzip() is None)
        check("T3 production package embedded", EXPECTED_TEMPLATE in names)
        check("T3 internal music manifest embedded", EXPECTED_LIBRARY in names)
        vaults = {n for n in names if n.startswith("assets/music/RESTAURATION-T3/1.0.0/") and n.endswith(".mp3.vault")}
        check("exactly four T3 sealed music tracks embedded", vaults == EXPECTED_VAULTS)
        t3_package = bundle.read(EXPECTED_TEMPLATE)
        check("embedded T3 package has expected selective ZIP signature", t3_package[:4] == b"PK\x03\x04")
        check("no raw MP3 in music asset tree", not any(n.startswith("assets/music/") and n.lower().endswith(".mp3") for n in names))
        forbidden = [n for n in names if n.lower().endswith(FORBIDDEN_EXTENSIONS)
                     or any(part in ("/" + n.lower()) for part in FORBIDDEN_PATH_PARTS)]
        check("no videos, WAV demos, fixtures, screenshots, proofs or reports", not forbidden)
        # Audio instructional guides are part of the established merchant guide UX;
        # approved music is only shipped as authenticated .vault blobs.
        guide_mp3 = [n for n in names if n.startswith("assets/mockup/assets/audio/") and n.lower().endswith(".mp3")]
        t3_guide_mp3 = [n for n in guide_mp3 if n.startswith("assets/mockup/assets/audio/r3-")]
        check("bundled MP3 files are only established scene-guide audio", all(Path(n).name.endswith("-guide.mp3") for n in guide_mp3))
        check("exactly four T3 guide recordings are present", len(t3_guide_mp3) == 4)
        check("no fixed/candidate voice folders in APK", not any("t3-voice-v1" in n.lower() or "t3-voice-candidates" in n.lower() for n in names))
        check("no source archive or non-production package ZIP", all(not n.lower().endswith(".zip") or n.startswith("assets/packages/") for n in names))
        package_sha = sha(t3_package)
        asset_listing = [n for n in names if n.startswith(("assets/packages/RESTAURATION-T3", "assets/music/RESTAURATION-T3/1.0.0/", "assets/mockup/assets/images/r3-", "assets/mockup/assets/audio/r3-"))]

    build_report = Path("docs/restauration_t3/BUILD_REPORT.txt").read_text(encoding="utf-8")
    expected_line = next((line for line in build_report.splitlines() if line.startswith("PACKAGE=")), "")
    expected_sha = expected_line.split("SHA256=")[-1] if "SHA256=" in expected_line else ""
    check("APK contains the exact audited T3 package build", bool(expected_sha) and package_sha == expected_sha)

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "RESTORATION-T3 RELEASE APK CONTENT AUDIT",
        f"APK={apk.name}", f"APK_BYTES={apk.stat().st_size}", f"APK_SHA256={sha(apk.read_bytes())}",
        f"EMBEDDED_T3_PACKAGE_SHA256={package_sha}", f"EMBEDDED_T3_PACKAGE_BYTES={len(t3_package)}",
        f"T3_SEALED_VAULT_COUNT={len(vaults)}", f"ALL_TEMPLATE_SCENE_GUIDE_AUDIO_COUNT={len(guide_mp3)}",
        f"T3_GUIDE_AUDIO_COUNT={len(t3_guide_mp3)}",
        "", "[T3_ASSET_INVENTORY]", *sorted(asset_listing), "", "[CHECKS]",
    ]
    lines.extend(f"{'PASS' if ok else 'FAIL'} {label}" for label, ok in checks)
    lines.append(f"TOTAL={len(checks)} PASS={sum(ok for _, ok in checks)} FAIL=0")
    REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(REPORT.read_text(encoding="utf-8"), end="")


if __name__ == "__main__":
    main()
