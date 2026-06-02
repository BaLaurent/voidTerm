package com.voidterm.voice;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-level HTTP transfer from HuggingFace (redirect handling, 200ms-throttled progress,
 * cooperative cancellation, atomic .tmp write). The single piece of knowledge shared by
 * every model download (Parakeet, Whisper). Extracted verbatim from ParakeetModelManager.
 */
public final class HttpModelDownloader {

    private static final String TAG = "HttpModelDownloader";
    private static final int DOWNLOAD_BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    public interface ProgressListener {
        void onProgress(long bytesDownloaded, long totalBytes);
    }

    private HttpModelDownloader() {}

    /**
     * Download {@code urlStr} into {@code destFile}, blocking on the caller's thread.
     * Throws {@link InterruptedIOException} when {@code cancelFlag} trips mid-transfer.
     */
    public static void download(String urlStr, File destFile, AtomicBoolean cancelFlag,
                                ProgressListener listener) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == 307 || responseCode == 308) {
                String redirectUrl = connection.getHeaderField("Location");
                connection.disconnect();
                connection = (HttpURLConnection) new URL(redirectUrl).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                responseCode = connection.getResponseCode();
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + responseCode + " for " + urlStr);
            }

            long totalBytes = connection.getContentLengthLong();

            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(destFile)) {
                byte[] buf = new byte[DOWNLOAD_BUFFER_SIZE];
                long downloaded = 0;
                int len;
                long lastProgressTime = 0;
                while ((len = in.read(buf)) > 0) {
                    if (cancelFlag.get()) {
                        throw new InterruptedIOException("Download cancelled");
                    }
                    out.write(buf, 0, len);
                    downloaded += len;
                    long now = System.currentTimeMillis();
                    if (now - lastProgressTime > 200) {
                        listener.onProgress(downloaded, totalBytes);
                        lastProgressTime = now;
                    }
                }
                out.flush();
                listener.onProgress(downloaded, totalBytes);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
