package com.voidterm.app;

import android.content.Intent;

import com.voidterm.contracts.DownloadJob;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class DownloadJobsTest {

    @Test
    public void fromIntent_parakeetType_returnsParakeetJob() {
        Intent i = new Intent()
                .putExtra(DownloadJobs.EXTRA_JOB_TYPE, ParakeetDownloadJob.ID);
        DownloadJob job = DownloadJobs.fromIntent(RuntimeEnvironment.getApplication(), i);
        assertNotNull(job);
        assertEquals(ParakeetDownloadJob.ID, job.id());
    }

    @Test
    public void fromIntent_unknownType_returnsNull() {
        Intent i = new Intent().putExtra(DownloadJobs.EXTRA_JOB_TYPE, "bogus");
        assertNull(DownloadJobs.fromIntent(RuntimeEnvironment.getApplication(), i));
    }

    @Test
    public void fromIntent_whisperType_returnsWhisperJobForModel() {
        Intent i = new Intent()
                .putExtra(DownloadJobs.EXTRA_JOB_TYPE, DownloadJobs.JOB_WHISPER)
                .putExtra(ModelDownloadService.EXTRA_MODEL_ID, "base");
        DownloadJob job = DownloadJobs.fromIntent(RuntimeEnvironment.getApplication(), i);
        assertNotNull(job);
        assertEquals("ggml-base.bin", job.id());
    }

    @Test
    public void fromIntent_whisperUnknownModel_returnsNull() {
        Intent i = new Intent()
                .putExtra(DownloadJobs.EXTRA_JOB_TYPE, DownloadJobs.JOB_WHISPER)
                .putExtra(ModelDownloadService.EXTRA_MODEL_ID, "nope");
        assertNull(DownloadJobs.fromIntent(RuntimeEnvironment.getApplication(), i));
    }
}
