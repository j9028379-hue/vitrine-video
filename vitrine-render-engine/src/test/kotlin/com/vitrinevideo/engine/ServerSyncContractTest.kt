package com.vitrinevideo.engine

import com.vitrinevideo.engine.capture.*
import com.vitrinevideo.engine.server.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerSyncContractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesSessionAndBuildsSubmitPayloadWithoutSecrets() {
        val serverResp = """
            {
              "job_id": "job_abc123",
              "session_id": "sess-001",
              "upload_urls": {
                "S1": {"r2_key": "uploads/m_1/sess-001/S1.mp4", "put_url": "https://acct.r2.cloudflarestorage.com/b/S1.mp4?X-Amz-Signature=11"},
                "S2": {"r2_key": "uploads/m_1/sess-001/S2.mp4", "put_url": "https://acct.r2.cloudflarestorage.com/b/S2.mp4?X-Amz-Signature=22"}
              }
            }
        """.trimIndent()
        val parsed = CreateSessionResponse.fromJson(serverResp)
        assertEquals("job_abc123", parsed.jobId)
        assertEquals(2, parsed.uploadTargets.size)

        val outDir = tmp.newFolder("out")
        val session = GuidedCaptureSession(
            sessionId = "sess-001",
            templateId = "COMMERCE-T3",
            templateVersion = "1.0.0",
            partitionJsonFile = File(outDir, "partition.json"),
            audioProfileJsonFile = null,
            resourcesDir = outDir,
            outputDir = outDir,
            rotationDbFile = File(outDir, "rot.db"),
            responses = mapOf("nom" to "Boutique Test"),
            captures = listOf("S1", "S2").map { sc ->
                SceneCaptureDescriptor(
                    sessionId = "sess-001",
                    templateId = "COMMERCE-T3",
                    templateVersion = "1.0.0",
                    sceneId = sc,
                    localVideoPath = "/tmp/$sc.mp4",
                    durationUs = 6_000_000L,
                    width = 720,
                    height = 1280,
                    rotation = 0,
                    audioPresent = sc == "S2",
                    sha256 = "a".repeat(64)
                )
            }
        )

        val submit = ServerSessionPayloadBuilder.buildSubmitCapturesJson(
            session = session,
            uploadTargets = parsed.uploadTargets,
            shaByScene = emptyMap()
        )
        assertEquals(2, submit.getJSONArray("captures").length())
        assertEquals("uploads/m_1/sess-001/S1.mp4", submit.getJSONArray("captures").getJSONObject(0).getString("r2_key"))

        val poll = ServerJobPollResult.fromJson(
            """{"job_id":"job_abc123","mode":"preview_proxy","dedup_cache_hit":true,"status":"COMPLETED","music_id":1,"sha256":"${"b".repeat(64)}","download_url":"https://acct.r2.cloudflarestorage.com/Film.mp4?X-Amz-Signature=99"}"""
        )
        assertEquals(ServerJobStatus.COMPLETED, poll.status)
        assertEquals(RenderJobMode.PREVIEW_PROXY, poll.mode)
        assertTrue(poll.dedupCacheHit)
        assertEquals(1, poll.musicId)
        assertNotNull(poll.downloadUrl)

        val desc = PreviewDescriptor.fromJson(
            """{"descriptor_version":"1.0","template_id":"COMMERCE-T3","template_version":"1.0.0","local_audio_protected":true,"format":{"width":720,"height":1280,"fps":30,"total_frames":900},"scenes":[{"id":"S1","debut":0,"fin_exclue":180}],"transitions":[],"text_layout":{"couleur":"#FFF8EE"}}"""
        )
        assertTrue(desc.localAudioProtected)
        assertEquals(900, desc.totalFrames)
        assertEquals(1, desc.scenesJson.length())
    }
}
