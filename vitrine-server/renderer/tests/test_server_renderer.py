"""Tests for universal server-side JSON interpreter, tight-bbox text engine,
audio rotation, 3-layer SHA-256 cache, preview_proxy vs final_export, and resilient worker guards.
"""

import json
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from partition_interpreter import interpret_partition
from text_overlay import TextOverlayEngine, resolve_fields
from audio_engine import select_and_commit_track
from cache_manager import LayeredArtifactCache
from render_pipeline import render_job
from worker_daemon import sign_hmac_hex, verify_hmac_sig


T3_PART = Path("/tmp/t3_server_test/montage/partition.json")
T5_PART = Path("/tmp/t5_server_test/montage/partition.json")
V12_PART = Path("/home/user/google_drive/T1_ENGINEERING_SPEC_V1_2.json")
T3_PROF = Path("/tmp/t3_server_test/audio/profil.json")
T3_FONT = Path("/tmp/t3_server_test/docs/assets/DMSerifText-Regular.ttf")
T3_CAPTURES_DIR = Path("/home/user/vitrine-engine-test-harness/app/src/main/assets/t3_fixture/captures")
T3_JOB_JSON = Path("/tmp/t3_server_test/golden/job.json")


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

    def test_empty_prix_omitted_completely_and_tight_bbox_returned(self):
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
        # Frame 710 is inside boutique window -> returns tight bbox (top_y, left_x, rgba_crop)
        band = eng.render_band_rgba(710)
        self.assertIsNotNone(band)
        top_y, left_x, rgba_crop = band
        self.assertGreaterEqual(top_y, 0)
        self.assertGreaterEqual(left_x, 0)
        self.assertLess(rgba_crop.shape[0], plan.height)
        self.assertGreater(int(rgba_crop[:, :, 3].max()), 0)

    def test_cyclic_audio_rotation_1_2_3_4_1_and_preview_non_mutation(self):
        if not T3_PROF.exists():
            return
        prof = json.loads(T3_PROF.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "rot.db"
            # Previews must NOT advance track_rotation
            self.assertEqual(select_and_commit_track(db, prof, "prev-1", advance_rotation=False)[1], 1)
            self.assertEqual(select_and_commit_track(db, prof, "prev-2", advance_rotation=False)[1], 1)
            # Final exports advance strictly 1 -> 2 -> 3 -> 4 -> 1 -> 2
            picks = [select_and_commit_track(db, prof, f"job-{i}", advance_rotation=True)[1] for i in range(6)]
            self.assertEqual(picks, [1, 2, 3, 4, 1, 2])
            self.assertEqual(select_and_commit_track(db, prof, "job-1", advance_rotation=True)[1], 2)

    def test_worker_hmac_verification_rejects_tamper_and_stale_ts(self):
        secret = "test-secret-01"
        ts = str(int(time.time()))
        body = b'{"job_id":"j1"}'
        sig = sign_hmac_hex(secret, f"{ts}.{body.decode()}")
        self.assertTrue(verify_hmac_sig(secret, ts, body, sig))
        self.assertFalse(verify_hmac_sig(secret, ts, b'{"job_id":"j2"}', sig))
        self.assertFalse(verify_hmac_sig(secret, str(int(time.time()) - 999), body, sig))

    def test_layered_cache_lru_eviction(self):
        with tempfile.TemporaryDirectory() as tmp:
            cache = LayeredArtifactCache(Path(tmp), max_bytes=1500, ttl_sec=3600)
            f1 = Path(tmp) / "a.bin"
            f2 = Path(tmp) / "b.bin"
            f1.write_bytes(b"x" * 900)
            f2.write_bytes(b"y" * 900)
            cache.put_file_atomic("base", "k1", "mp4", f1)
            time.sleep(0.02)
            cache.put_file_atomic("base", "k2", "mp4", f2)
            res = cache.evict_if_needed()
            self.assertLessEqual(res["remaining_bytes"], 1500)
            self.assertIsNone(cache.get_file("base", "k1", "mp4"))
            self.assertIsNotNone(cache.get_file("base", "k2", "mp4"))

    def test_preview_proxy_cache_hits_and_rush_invalidation(self):
        if not (T3_PART.exists() and T3_CAPTURES_DIR.exists() and T3_JOB_JSON.exists()):
            return
        caps = {f"S{i}": T3_CAPTURES_DIR / f"S{i}.mp4" for i in range(1, 5)}
        resp1 = json.loads(T3_JOB_JSON.read_text(encoding="utf-8"))["reponses"]
        resp2 = {**resp1, "prix": "8 000 FCFA"}

        with tempfile.TemporaryDirectory() as tmp:
            t = Path(tmp)
            cache = t / "cache"
            db = t / "rot.db"

            # 1. Cold preview_proxy
            c1 = render_job(T3_PART, T3_PROF, Path("/tmp/t3_server_test"), caps, resp1, t / "p1", db, "p1", mode="preview_proxy", cache_dir=cache)["controle"]
            self.assertEqual(c1["ffprobe"]["width"], 360)
            self.assertEqual(c1["ffprobe"]["height"], 640)
            self.assertEqual(c1["ffprobe"]["frames"], 450)
            self.assertFalse(c1["base_cache_hit"])
            self.assertFalse(c1["audio_cache_hit"])

            # 2. Text-only edit -> base_cache_hit=True AND audio_cache_hit=True!
            c2 = render_job(T3_PART, T3_PROF, Path("/tmp/t3_server_test"), caps, resp2, t / "p2", db, "p2", mode="preview_proxy", cache_dir=cache)["controle"]
            self.assertTrue(c2["base_cache_hit"])
            self.assertTrue(c2["audio_cache_hit"])
            self.assertFalse(c2["dedup_cache_hit"])

            # 3. Identical repeat -> instant dedup_cache_hit=True!
            c3 = render_job(T3_PART, T3_PROF, Path("/tmp/t3_server_test"), caps, resp2, t / "p3", db, "p3", mode="preview_proxy", cache_dir=cache)["controle"]
            self.assertTrue(c3["dedup_cache_hit"])

            # 4. Rush change (swap S2 clip file path to S3 clip) -> invalidates base_cache_hit!
            caps_swapped = {**caps, "S2": caps["S3"]}
            c4 = render_job(T3_PART, T3_PROF, Path("/tmp/t3_server_test"), caps_swapped, resp2, t / "p4", db, "p4", mode="preview_proxy", cache_dir=cache)["controle"]
            self.assertFalse(c4["base_cache_hit"])
            self.assertTrue(c4["audio_cache_hit"])


if __name__ == "__main__":
    unittest.main()
