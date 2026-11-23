"""Tests for universal server-side JSON interpreter, text engine, audio rotation, and T3/T5 parity."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from partition_interpreter import interpret_partition
from text_overlay import TextOverlayEngine, resolve_fields
from audio_engine import select_and_commit_track
from worker_daemon import sign_hmac_hex, verify_hmac_sig


T3_PART = Path("/tmp/t3_server_test/montage/partition.json")
T5_PART = Path("/tmp/t5_server_test/montage/partition.json")
V12_PART = Path("/home/user/google_drive/T1_ENGINEERING_SPEC_V1_2.json")
T3_PROF = Path("/tmp/t3_server_test/audio/profil.json")
T3_FONT = Path("/tmp/t3_server_test/docs/assets/DMSerifText-Regular.ttf")


class ServerRendererTest(unittest.TestCase):
    def test_interprets_t3_t5_and_v12_partitions(self):
        if T3_PART.exists():
            p3 = interpret_partition(T3_PART)
            self.assertEqual(p3.template_id, "COMMERCE-T3")
            self.assertEqual(p3.total_frames, 900)
            self.assertEqual(len(p3.transitions), 3)
            self.assertIn(1, p3.grid_rules)
        if T5_PART.exists():
            p5 = interpret_partition(T5_PART)
            self.assertEqual(p5.template_id, "COMMERCE-T5")
            self.assertEqual(p5.total_frames, 900)
            self.assertTrue(len(p5.shots_by_scene["S1"]) >= 3)
        if V12_PART.exists():
            p12 = interpret_partition(V12_PART)
            self.assertEqual(p12.fps, 30)

    def test_empty_prix_omitted_completely(self):
        if not (T3_PART.exists() and T3_FONT.exists()):
            return
        plan = interpret_partition(T3_PART)
        responses = {
            "nom": "Boutique Test",
            "produit": "Savon Karité",
            "prix": "",
            "demonstration": "Mousse douce et naturelle.",
            "quartier": "Parakou",
            "whatsapp": "+229 01 02 03 04",
        }
        fields = resolve_fields(plan, responses)
        self.assertEqual(fields["prix"], "")
        self.assertEqual(fields["contact"], "WhatsApp\n+229 01 02 03 04")
        eng = TextOverlayEngine(plan, responses, T3_FONT)
        # Frame 650 is inside prix window (635..694) -> must return None!
        self.assertIsNone(eng.render_band_rgba(650))
        # Frame 710 is inside boutique window -> must render!
        band = eng.render_band_rgba(710)
        self.assertIsNotNone(band)
        self.assertGreater(int(band[1][:, :, 3].max()), 0)

    def test_cyclic_audio_rotation_1_2_3_4_1(self):
        if not T3_PROF.exists():
            return
        prof = json.loads(T3_PROF.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "rot.db"
            picks = [select_and_commit_track(db, prof, f"job-{i}")[1] for i in range(6)]
            self.assertEqual(picks, [1, 2, 3, 4, 1, 2])
            # Idempotent repeat for same job_id
            self.assertEqual(select_and_commit_track(db, prof, "job-1")[1], 2)

    def test_worker_hmac_verification_rejects_tamper_and_stale_ts(self):
        import time
        secret = "test-secret-01"
        ts = str(int(time.time()))
        body = b'{"job_id":"j1"}'
        sig = sign_hmac_hex(secret, f"{ts}.{body.decode()}")
        self.assertTrue(verify_hmac_sig(secret, ts, body, sig))
        self.assertFalse(verify_hmac_sig(secret, ts, b'{"job_id":"j2"}', sig))
        self.assertFalse(verify_hmac_sig(secret, str(int(time.time()) - 999), body, sig))


if __name__ == "__main__":
    unittest.main()
