# Téléchargement in-app des modèles Whisper — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permettre le téléchargement in-app des modèles Whisper (ggml) depuis l'écran Settings, comme pour Parakeet, en généralisant le service de download (policy/mechanism).

**Architecture:** On casse `ParakeetDownloadService` en un mécanisme générique (`ModelDownloadService`) qui exécute un `DownloadJob` (policy). Parakeet et Whisper deviennent deux jobs. Le transfert HTTP HuggingFace est extrait dans `HttpModelDownloader`. Un catalogue (`WhisperModelCatalog`) et un gestionnaire d'état (`WhisperModelManager`) couvrent la partie Whisper. Activer un modèle Whisper bascule aussi le moteur sur `whisper` (Parakeet est désormais le défaut).

**Tech Stack:** Java 17, Android SDK 34, JUnit 4 + Robolectric 4.12 + Mockito 5. Build : `./gradlew`.

**Spec source :** `docs/superpowers/specs/2026-06-02-whisper-model-download-design.md`

**Convention build/test du repo :**
- Compiler : `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
- Tests unitaires : `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest`
- Un seul test : `... ./gradlew testDebugUnitTest --tests "com.voidterm.voice.WhisperModelCatalogTest"`
- Les commandes ci-dessous omettent le préfixe `JAVA_HOME=...` ; ajoute-le si ton JDK par défaut > 17.

---

## File Structure

**Nouveaux fichiers**
- `app/src/main/java/com/voidterm/contracts/FileSpec.java` — DTO plain-data : un fichier à télécharger.
- `app/src/main/java/com/voidterm/contracts/DownloadJob.java` — interface policy : *quoi* télécharger.
- `app/src/main/java/com/voidterm/voice/HttpModelDownloader.java` — mécanisme transfert HTTP HF (extrait de `ParakeetModelManager`).
- `app/src/main/java/com/voidterm/voice/WhisperModelCatalog.java` — data : les 31 variantes ggml.
- `app/src/main/java/com/voidterm/voice/WhisperModelManager.java` — état filesystem des modèles whisper.
- `app/src/main/java/com/voidterm/app/ParakeetDownloadJob.java` — policy Parakeet (4 fichiers ONNX).
- `app/src/main/java/com/voidterm/app/WhisperDownloadJob.java` — policy Whisper (1 modèle + bascule moteur).
- `app/src/main/java/com/voidterm/app/DownloadJobs.java` — factory `fromIntent(Intent)`.
- `app/src/main/java/com/voidterm/app/ModelDownloadService.java` — mécanisme foreground générique (renomme `ParakeetDownloadService`).
- `app/src/main/java/com/voidterm/app/WhisperCatalogView.java` — vue accordéon du catalogue (par famille).
- Tests : `WhisperModelCatalogTest`, `WhisperModelManagerTest`, `DownloadJobsTest`, `FileSpecTest`.

**Fichiers modifiés**
- `app/src/main/java/com/voidterm/voice/ParakeetModelManager.java` — délègue le transfert à `HttpModelDownloader`, expose `fileSpecs(Context)`.
- `app/src/main/java/com/voidterm/app/SettingsActivity.java` — model section : intègre `WhisperCatalogView`, route le broadcast, aligne le file picker.
- `app/src/main/AndroidManifest.xml` — `<service>` renommé.
- `app/src/main/java/com/voidterm/app/SettingsDialog.java` — clé(s) éventuelle(s) (voir Task 9).
- `CLAUDE.md` — doc.

**Suppression**
- `app/src/main/java/com/voidterm/app/ParakeetDownloadService.java` — remplacé par `ModelDownloadService` (Task 7).

---

## PHASE A — Refactor policy/mechanism (Parakeet behavior-preserving)

### Task 1 : DTO `FileSpec` (contracts)

**Files:**
- Create: `app/src/main/java/com/voidterm/contracts/FileSpec.java`
- Test: `app/src/test/java/com/voidterm/contracts/FileSpecTest.java`

- [ ] **Step 1 : Écrire le test qui échoue**

```java
package com.voidterm.contracts;

import org.junit.Test;
import java.io.File;
import static org.junit.Assert.assertEquals;

public class FileSpecTest {
    @Test
    public void storesAllFields() {
        File dest = new File("/tmp/ggml-base.bin");
        FileSpec spec = new FileSpec("https://example/ggml-base.bin", dest, "base");
        assertEquals("https://example/ggml-base.bin", spec.url);
        assertEquals(dest, spec.destFile);
        assertEquals("base", spec.label);
    }
}
```

- [ ] **Step 2 : Lancer le test, vérifier l'échec de compilation**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.contracts.FileSpecTest"`
Expected: FAIL — `FileSpec` n'existe pas.

- [ ] **Step 3 : Écrire l'implémentation minimale**

```java
package com.voidterm.contracts;

import java.io.File;

/** Plain-data description of one file to download (DTO crossing the download boundary). */
public final class FileSpec {
    public final String url;
    public final File destFile;
    public final String label;

    public FileSpec(String url, File destFile, String label) {
        this.url = url;
        this.destFile = destFile;
        this.label = label;
    }
}
```

- [ ] **Step 4 : Lancer le test, vérifier le succès**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.contracts.FileSpecTest"`
Expected: PASS.

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/voidterm/contracts/FileSpec.java app/src/test/java/com/voidterm/contracts/FileSpecTest.java
git commit -m "feat(download): add FileSpec DTO in contracts"
```

---

### Task 2 : Interface `DownloadJob` (contracts)

**Files:**
- Create: `app/src/main/java/com/voidterm/contracts/DownloadJob.java`

Pas de test unitaire (interface pure ; couverte par les implémentations Task 4/10).

- [ ] **Step 1 : Écrire l'interface**

```java
package com.voidterm.contracts;

import android.content.Context;
import java.util.List;

/**
 * Policy for a model download: describes WHAT to download and what to do on success.
 * The MECHANISM (foreground service, wakelock, notification, HTTP transfer) lives in
 * ModelDownloadService + HttpModelDownloader. This is a strategy (composition over
 * inheritance), not a plain-data DTO — the DTO it carries is {@link FileSpec}.
 */
public interface DownloadJob {
    /** Stable identifier of the download target (e.g. "parakeet" or a whisper file id). */
    String id();

    /** Human-readable name for the notification (e.g. "Parakeet model", "Whisper base"). */
    String displayName();

    /** Ordered list of files to fetch. Already-present files are skipped by the runner. */
    List<FileSpec> files();

    /** Called once after all files succeed. Persists prefs (reload flag, active model, engine). */
    void onComplete(Context context);
}
```

- [ ] **Step 2 : Vérifier la compilation**

Run: `./gradlew compileDebugJavaWithJavac`
Expected: PASS.

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/voidterm/contracts/DownloadJob.java
git commit -m "feat(download): add DownloadJob policy interface in contracts"
```

---

### Task 3 : Extraire `HttpModelDownloader` (voice)

But : déplacer le transfert HTTP bas-niveau hors de `ParakeetModelManager` vers une classe partagée, sans changer le comportement. Refactor (pas de TDD — réseau non unit-testable, couvert par re-test manuel Parakeet en Task 7).

**Files:**
- Create: `app/src/main/java/com/voidterm/voice/HttpModelDownloader.java`
- Modify: `app/src/main/java/com/voidterm/voice/ParakeetModelManager.java`

- [ ] **Step 1 : Créer `HttpModelDownloader` avec le code extrait verbatim**

```java
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
```

- [ ] **Step 2 : Dans `ParakeetModelManager`, remplacer le corps de `downloadFile` par une délégation**

Dans `ParakeetModelManager.java`, supprimer la méthode privée `downloadFile(...)` et l'interface `DownloadProgressListener` (lignes ~163-223), et remplacer l'appel dans `download()` (lignes ~130-131) :

Remplacer :
```java
                try {
                    downloadFile(urlStr, tempFile, cancelFlag, (bytesDownloaded, totalBytes) ->
                            callback.onProgress(fileName, fileIdx, totalFiles, bytesDownloaded, totalBytes));
```
par :
```java
                try {
                    HttpModelDownloader.download(urlStr, tempFile, cancelFlag, (bytesDownloaded, totalBytes) ->
                            callback.onProgress(fileName, fileIdx, totalFiles, bytesDownloaded, totalBytes));
```

Puis supprimer les constantes désormais inutilisées de `ParakeetModelManager` si elles ne servent plus ailleurs : `DOWNLOAD_BUFFER_SIZE`, `CONNECT_TIMEOUT_MS`, `READ_TIMEOUT_MS` (vérifier par recherche avant suppression).

- [ ] **Step 3 : Vérifier la compilation**

Run: `./gradlew compileDebugJavaWithJavac`
Expected: PASS, zéro warning « unused ».

- [ ] **Step 4 : Commit**

```bash
git add app/src/main/java/com/voidterm/voice/HttpModelDownloader.java app/src/main/java/com/voidterm/voice/ParakeetModelManager.java
git commit -m "refactor(download): extract HttpModelDownloader (shared HF transfer)"
```

---

### Task 4 : `ParakeetDownloadJob` + `ParakeetModelManager.fileSpecs()` (policy Parakeet)

**Files:**
- Modify: `app/src/main/java/com/voidterm/voice/ParakeetModelManager.java`
- Create: `app/src/main/java/com/voidterm/app/ParakeetDownloadJob.java`

- [ ] **Step 1 : Exposer `fileSpecs(Context)` sur `ParakeetModelManager`**

Ajouter (les tableaux `REQUIRED_FILES`/`DOWNLOAD_URLS` et `getModelDir` existent déjà) :

```java
    /** The files to download for Parakeet, as boundary DTOs. */
    public static java.util.List<com.voidterm.contracts.FileSpec> fileSpecs(Context context) {
        File modelDir = getModelDir(context);
        java.util.List<com.voidterm.contracts.FileSpec> specs = new java.util.ArrayList<>();
        for (int i = 0; i < REQUIRED_FILES.length; i++) {
            specs.add(new com.voidterm.contracts.FileSpec(
                    DOWNLOAD_URLS[i], new File(modelDir, REQUIRED_FILES[i]), REQUIRED_FILES[i]));
        }
        return specs;
    }
```

- [ ] **Step 2 : Écrire `ParakeetDownloadJob`**

```java
package com.voidterm.app;

import android.content.Context;

import com.voidterm.contracts.DownloadJob;
import com.voidterm.contracts.FileSpec;
import com.voidterm.voice.ParakeetModelManager;

import java.util.List;

/** DownloadJob policy for the Parakeet ONNX bundle (4 fixed files). */
public final class ParakeetDownloadJob implements DownloadJob {

    public static final String ID = "parakeet";

    @Override public String id() { return ID; }

    @Override public String displayName() { return "Parakeet model"; }

    @Override public List<FileSpec> files() {
        // Context is supplied at construction by the factory; see DownloadJobs.
        return ParakeetModelManager.fileSpecs(context);
    }

    @Override public void onComplete(Context context) {
        context.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true).apply();
    }

    private final Context context;
    public ParakeetDownloadJob(Context context) {
        this.context = context.getApplicationContext();
    }
}
```

- [ ] **Step 3 : Vérifier la compilation**

Run: `./gradlew compileDebugJavaWithJavac`
Expected: PASS.

- [ ] **Step 4 : Commit**

```bash
git add app/src/main/java/com/voidterm/voice/ParakeetModelManager.java app/src/main/java/com/voidterm/app/ParakeetDownloadJob.java
git commit -m "feat(download): add ParakeetDownloadJob policy + fileSpecs()"
```

---

### Task 5 : Factory `DownloadJobs.fromIntent` (app)

**Files:**
- Create: `app/src/main/java/com/voidterm/app/DownloadJobs.java`
- Test: `app/src/test/java/com/voidterm/app/DownloadJobsTest.java`

Note : `WhisperDownloadJob` n'existe pas encore (Task 10). Cette tâche ne gère que le type `parakeet` ; le branchement whisper est ajouté en Task 11 (avec son test).

- [ ] **Step 1 : Écrire le test qui échoue (Robolectric)**

```java
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
}
```

- [ ] **Step 2 : Lancer, vérifier l'échec**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.app.DownloadJobsTest"`
Expected: FAIL — `DownloadJobs` n'existe pas.

- [ ] **Step 3 : Écrire la factory**

```java
package com.voidterm.app;

import android.content.Context;
import android.content.Intent;

import com.voidterm.contracts.DownloadJob;

/** Rebuilds a DownloadJob from a Service Intent (Services receive Intents, not objects). */
public final class DownloadJobs {

    public static final String EXTRA_JOB_TYPE = "job_type";
    public static final String EXTRA_MODEL_ID = "model_id";

    private DownloadJobs() {}

    public static DownloadJob fromIntent(Context context, Intent intent) {
        String type = intent.getStringExtra(EXTRA_JOB_TYPE);
        if (ParakeetDownloadJob.ID.equals(type)) {
            return new ParakeetDownloadJob(context);
        }
        return null;
    }
}
```

- [ ] **Step 4 : Lancer, vérifier le succès**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.app.DownloadJobsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/voidterm/app/DownloadJobs.java app/src/test/java/com/voidterm/app/DownloadJobsTest.java
git commit -m "feat(download): add DownloadJobs.fromIntent factory (parakeet)"
```

---

### Task 6 : `ModelDownloadService` — généraliser le service (renomme `ParakeetDownloadService`)

But : remplacer `ParakeetDownloadService` par un service générique qui exécute un `DownloadJob`. La boucle de download (skip-existing, .tmp, rename, cancel) migre depuis `ParakeetModelManager.download()` vers le service. Refactor + manuel (Task 7 re-teste Parakeet).

**Files:**
- Create: `app/src/main/java/com/voidterm/app/ModelDownloadService.java`
- Delete (à la fin de la tâche): `app/src/main/java/com/voidterm/app/ParakeetDownloadService.java`

- [ ] **Step 1 : Écrire `ModelDownloadService`**

Conserve channel `voidterm_download`, NOTIFICATION_ID **2**, wakelock, broadcasts. Nouveautés : exécute un `DownloadJob` via la factory, et le broadcast porte `EXTRA_MODEL_ID` pour cibler la ligne d'UI.

```java
package com.voidterm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.voidterm.contracts.DownloadJob;
import com.voidterm.contracts.FileSpec;
import com.voidterm.voice.HttpModelDownloader;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns ANY model download (Parakeet bundle or one Whisper model).
 * MECHANISM only: foreground lifecycle, wakelock, notification, cancellation, the
 * skip/.tmp/rename loop. The POLICY (what to download) is a {@link DownloadJob} rebuilt
 * from the start Intent via {@link DownloadJobs}.
 */
public class ModelDownloadService extends Service {

    private static final String TAG = "ModelDownloadService";
    private static final String CHANNEL_ID = "voidterm_download";
    private static final int NOTIFICATION_ID = 2;
    private static final long WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L;

    public static final String ACTION_START_DOWNLOAD = "com.voidterm.action.START_DOWNLOAD";
    public static final String ACTION_CANCEL_DOWNLOAD = "com.voidterm.action.CANCEL_DOWNLOAD";

    public static final String BROADCAST_PROGRESS = "com.voidterm.action.DOWNLOAD_PROGRESS";
    public static final String BROADCAST_COMPLETE = "com.voidterm.action.DOWNLOAD_COMPLETE";
    public static final String BROADCAST_ERROR = "com.voidterm.action.DOWNLOAD_ERROR";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_PERCENT = "percent";       // current-file %, -1 if unknown
    public static final String EXTRA_MODEL_ID = "model_id";     // job id, routes UI updates

    /** Sentinel used when a download is cancelled. */
    public static final String CANCELLED = "Cancelled";

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile String lastProgressText;
    private static volatile String runningJobId;

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() { return running.get(); }
    public static String lastProgressText() { return lastProgressText; }
    /** Id of the job currently downloading, or null. Lets the UI know which line is active. */
    public static String runningJobId() { return runningJobId; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_CANCEL_DOWNLOAD.equals(action)) {
            cancelFlag.set(true);
            return START_NOT_STICKY;
        }

        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "Download already running, ignoring start");
            return START_NOT_STICKY;
        }

        DownloadJob job = DownloadJobs.fromIntent(getApplicationContext(), intent);
        if (job == null) {
            Log.e(TAG, "No valid DownloadJob in intent");
            running.set(false);
            return START_NOT_STICKY;
        }

        cancelFlag.set(false);
        runningJobId = job.id();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildProgressNotification(job.displayName(),
                "Starting download…", -1));
        acquireWakeLock();
        startDownloadThread(job);
        return START_NOT_STICKY;
    }

    private void startDownloadThread(DownloadJob job) {
        final Context ctx = getApplicationContext();
        new Thread(() -> runJob(ctx, job), "ModelDownload").start();
    }

    /** The generic skip/.tmp/rename loop, migrated from ParakeetModelManager.download(). */
    private void runJob(Context ctx, DownloadJob job) {
        List<FileSpec> files = job.files();
        int total = files.size();
        try {
            for (int i = 0; i < total; i++) {
                if (cancelFlag.get()) { finishCancelled(job); return; }

                FileSpec spec = files.get(i);
                File dest = spec.destFile;
                File dir = dest.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) {
                    finishError(job, "Failed to create model directory");
                    return;
                }
                if (dest.exists() && dest.length() > 0) {
                    broadcastProgress(job, spec.label + " ready (" + (i + 1) + "/" + total + ")", -1);
                    continue;
                }

                File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
                final int idx = i;
                try {
                    HttpModelDownloader.download(spec.url, tmp, cancelFlag, (done, totalBytes) -> {
                        int percent = totalBytes > 0 ? (int) (done * 100 / totalBytes) : -1;
                        String mb = String.format(Locale.US, "%.1f", done / 1048576f);
                        String totMb = totalBytes > 0
                                ? String.format(Locale.US, "%.1f", totalBytes / 1048576f) : "?";
                        broadcastProgress(job, "Downloading " + spec.label + " ("
                                + (idx + 1) + "/" + total + "): " + mb + " / " + totMb + " MB", percent);
                    });
                    if (!tmp.renameTo(dest)) {
                        throw new IOException("Failed to rename " + tmp.getName());
                    }
                    broadcastProgress(job, spec.label + " complete (" + (i + 1) + "/" + total + ")", -1);
                } catch (IOException e) {
                    if (tmp.exists()) tmp.delete();
                    if (cancelFlag.get()) { finishCancelled(job); return; }
                    Log.e(TAG, "Download failed", e);
                    finishError(job, "Failed to download " + spec.label + ": " + e.getMessage());
                    return;
                }
            }
            job.onComplete(ctx);
            broadcast(BROADCAST_COMPLETE, job.id(), "All files downloaded", 100);
            finish(job.displayName() + " ready", false);
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            finishError(job, "Download failed: " + e.getMessage());
        }
    }

    private void broadcastProgress(DownloadJob job, String text, int percent) {
        lastProgressText = text;
        updateNotification(job.displayName(), text, percent);
        broadcast(BROADCAST_PROGRESS, job.id(), text, percent);
    }

    private void finishError(DownloadJob job, String error) {
        broadcast(BROADCAST_ERROR, job.id(), error, -1);
        finish("Download failed: " + error, false);
    }

    private void finishCancelled(DownloadJob job) {
        broadcast(BROADCAST_ERROR, job.id(), CANCELLED, -1);
        finish(null, true);
    }

    private void finish(String doneText, boolean cancelled) {
        releaseWakeLock();
        lastProgressText = null;
        runningJobId = null;
        running.set(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (!cancelled && doneText != null) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildDoneNotification(doneText));
        }
        stopSelf();
    }

    // --- Wake lock ---

    private void acquireWakeLock() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voidterm:model-download");
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    // --- Notifications ---

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Model Download", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildProgressNotification(String title, String text, int percent) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, ModelDownloadService.class).setAction(ACTION_CANCEL_DOWNLOAD),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloading " + title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel);
        if (percent >= 0) b.setProgress(100, percent, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void updateNotification(String title, String text, int percent) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildProgressNotification(title, text, percent));
    }

    private Notification buildDoneNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VoidTerm")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build();
    }

    // --- Broadcast ---

    private void broadcast(String action, String modelId, String text, int percent) {
        Intent i = new Intent(action)
                .setPackage(getPackageName())
                .putExtra(EXTRA_MODEL_ID, modelId)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PERCENT, percent);
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        running.set(false);
        runningJobId = null;
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}
```

- [ ] **Step 2 : Mettre à jour `ParakeetModelManager.download()` — déprécation**

La boucle a migré dans le service. Pour ne pas laisser de code mort, supprimer de `ParakeetModelManager` : la méthode `download(...)`, l'interface `ProgressCallback`, la constante `CANCELLED` (désormais sur le service). **Garder** `isModelComplete`, `getModelDir`, `getDownloadedSize`, `deleteModels`, `fileSpecs`, `REQUIRED_FILES`, `DOWNLOAD_URLS`. Vérifier qu'aucun autre appelant n'utilise les membres supprimés :

Run: `rg -n "ParakeetModelManager.(download|CANCELLED|ProgressCallback)" --glob "*.java"`
Expected: aucun résultat hors fichiers de cette tâche.

- [ ] **Step 3 : Supprimer `ParakeetDownloadService.java`**

```bash
git rm app/src/main/java/com/voidterm/app/ParakeetDownloadService.java
```

- [ ] **Step 4 : Compilation (échoue tant que SettingsActivity + manifest ne sont pas migrés)**

Run: `./gradlew compileDebugJavaWithJavac`
Expected: FAIL — références à `ParakeetDownloadService` dans `SettingsActivity` + manifest. Corrigées en Task 7. **Ne pas committer ici** ; Task 7 ferme le même commit logique.

---

### Task 7 : Migrer `AndroidManifest` + `SettingsActivity` sur `ModelDownloadService` (même commit que Task 6)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/voidterm/app/SettingsActivity.java`

- [ ] **Step 1 : Manifest — renommer le service**

Remplacer :
```xml
        <service
            android:name=".app.ParakeetDownloadService"
            android:exported="false" />
```
par :
```xml
        <service
            android:name=".app.ModelDownloadService"
            android:exported="false" />
```

- [ ] **Step 2 : `SettingsActivity` — remplacer toutes les références `ParakeetDownloadService` → `ModelDownloadService`**

Dans `onResume()` (lignes ~172-188), `buildModelSection()` (lignes ~350-377), `onDownloadBroadcast()` (lignes ~454-470) : remplacer `ParakeetDownloadService` par `ModelDownloadService` partout. Le démarrage du download Parakeet doit désormais passer le type de job :

Remplacer (dans `parakeetDownloadBtn.setOnClickListener`) :
```java
            startForegroundService(new Intent(this, ParakeetDownloadService.class)
                    .setAction(ParakeetDownloadService.ACTION_START_DOWNLOAD));
```
par :
```java
            startForegroundService(new Intent(this, ModelDownloadService.class)
                    .setAction(ModelDownloadService.ACTION_START_DOWNLOAD)
                    .putExtra(DownloadJobs.EXTRA_JOB_TYPE, ParakeetDownloadJob.ID));
```

Et le cancel :
```java
            startService(new Intent(this, ModelDownloadService.class)
                    .setAction(ModelDownloadService.ACTION_CANCEL_DOWNLOAD));
```

- [ ] **Step 3 : Compilation**

Run: `./gradlew compileDebugJavaWithJavac`
Expected: PASS.

- [ ] **Step 4 : Build APK + lancer toute la suite de tests**

Run: `./gradlew assembleDebug && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tous les tests passent (dont `DownloadJobsTest`).

- [ ] **Step 5 : Commit (referme le refactor Task 6+7)**

```bash
git add -A
git commit -m "refactor(download): generalize service to ModelDownloadService + DownloadJob

Parakeet download now runs as a ParakeetDownloadJob through the generic
ModelDownloadService. Behavior-preserving: same channel id, notification id 2,
action strings, broadcast extras. Manifest + SettingsActivity migrated in this commit."
```

- [ ] **Step 6 : ⚠️ RE-TEST MANUEL SUR QUEST (critère d'acceptation, non automatisable)**

Installer (`adb install -r app/build/outputs/apk/debug/app-debug.apk`) et vérifier le chemin Parakeet de bout en bout :
1. Settings → Voice Engine → Parakeet → « Delete Models » si présents, puis « Download Models (~534 MB) ».
2. Notification de progression visible, action **Cancel** fonctionne (teardown silencieux, `.tmp` nettoyés).
3. Relancer, laisser finir → notification « Parakeet model ready », modèle utilisable en transcription.
4. Quitter Settings pendant le download → il continue (foreground), revenir → l'UI re-sync.

---

## PHASE B — Catalogue & download Whisper

### Task 8 : `WhisperModelCatalog` (voice)

**Files:**
- Create: `app/src/main/java/com/voidterm/voice/WhisperModelCatalog.java`
- Test: `app/src/test/java/com/voidterm/voice/WhisperModelCatalogTest.java`

- [ ] **Step 1 : Écrire le test qui échoue**

```java
package com.voidterm.voice;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WhisperModelCatalogTest {

    @Test
    public void hasAllThirtyOneVariants() {
        assertEquals(31, WhisperModelCatalog.ALL.size());
    }

    @Test
    public void idsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            assertTrue("duplicate id: " + m.id, ids.add(m.id));
        }
    }

    @Test
    public void fileNamesStartWithGgmlAndUrlMatches() {
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            assertTrue(m.fileName.startsWith("ggml-"));
            assertTrue(m.fileName.endsWith(".bin"));
            assertEquals(WhisperModelCatalog.BASE_URL + m.fileName, m.url());
            assertTrue("size must be positive: " + m.id, m.sizeMb > 0);
        }
    }

    @Test
    public void familiesAreKnown() {
        Set<String> known = new HashSet<>();
        known.add("tiny"); known.add("base"); known.add("small");
        known.add("medium"); known.add("large");
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            assertTrue("unknown family: " + m.family, known.contains(m.family));
        }
    }

    @Test
    public void englishOnlyFlagMatchesFileName() {
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            assertEquals(m.fileName.contains(".en"), m.englishOnly);
        }
    }
}
```

- [ ] **Step 2 : Lancer, vérifier l'échec**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.voice.WhisperModelCatalogTest"`
Expected: FAIL — `WhisperModelCatalog` n'existe pas.

- [ ] **Step 3 : Écrire le catalogue (data vérifiée HF le 2026-06-02)**

```java
package com.voidterm.voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Static catalog of downloadable Whisper ggml models (HuggingFace ggerganov/whisper.cpp). */
public final class WhisperModelCatalog {

    public static final String BASE_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    public static final class WhisperModel {
        public final String id;          // e.g. "base", "base.en", "base-q5_1"
        public final String fileName;    // e.g. "ggml-base.bin"
        public final String displayName; // e.g. "base", "base.en (q5_1)"
        public final int sizeMb;
        public final String family;      // tiny|base|small|medium|large
        public final boolean quantized;
        public final boolean englishOnly;

        WhisperModel(String fileName, String displayName, int sizeMb, String family,
                     boolean quantized, boolean englishOnly) {
            this.fileName = fileName;
            this.id = fileName.substring("ggml-".length(), fileName.length() - ".bin".length());
            this.displayName = displayName;
            this.sizeMb = sizeMb;
            this.family = family;
            this.quantized = quantized;
            this.englishOnly = englishOnly;
        }

        public String url() { return BASE_URL + fileName; }
    }

    public static final List<WhisperModel> ALL;

    private static void add(List<WhisperModel> l, String file, String label, int mb,
                            String family, boolean q, boolean en) {
        l.add(new WhisperModel(file, label, mb, family, q, en));
    }

    static {
        List<WhisperModel> l = new ArrayList<>();
        // tiny
        add(l, "ggml-tiny.bin", "tiny", 78, "tiny", false, false);
        add(l, "ggml-tiny.en.bin", "tiny.en", 78, "tiny", false, true);
        add(l, "ggml-tiny-q5_1.bin", "tiny (q5_1)", 32, "tiny", true, false);
        add(l, "ggml-tiny-q8_0.bin", "tiny (q8_0)", 44, "tiny", true, false);
        add(l, "ggml-tiny.en-q5_1.bin", "tiny.en (q5_1)", 32, "tiny", true, true);
        add(l, "ggml-tiny.en-q8_0.bin", "tiny.en (q8_0)", 44, "tiny", true, true);
        // base
        add(l, "ggml-base.bin", "base", 148, "base", false, false);
        add(l, "ggml-base.en.bin", "base.en", 148, "base", false, true);
        add(l, "ggml-base-q5_1.bin", "base (q5_1)", 60, "base", true, false);
        add(l, "ggml-base-q8_0.bin", "base (q8_0)", 82, "base", true, false);
        // small
        add(l, "ggml-small.bin", "small", 488, "small", false, false);
        add(l, "ggml-small.en.bin", "small.en", 488, "small", false, true);
        add(l, "ggml-small-q5_1.bin", "small (q5_1)", 190, "small", true, false);
        add(l, "ggml-small-q8_0.bin", "small (q8_0)", 264, "small", true, false);
        add(l, "ggml-small.en-q5_1.bin", "small.en (q5_1)", 190, "small", true, true);
        add(l, "ggml-small.en-q8_0.bin", "small.en (q8_0)", 264, "small", true, true);
        // medium
        add(l, "ggml-medium.bin", "medium", 1530, "medium", false, false);
        add(l, "ggml-medium.en.bin", "medium.en", 1530, "medium", false, true);
        add(l, "ggml-medium-q5_0.bin", "medium (q5_0)", 539, "medium", true, false);
        add(l, "ggml-medium-q8_0.bin", "medium (q8_0)", 823, "medium", true, false);
        add(l, "ggml-medium.en-q5_0.bin", "medium.en (q5_0)", 539, "medium", true, true);
        add(l, "ggml-medium.en-q8_0.bin", "medium.en (q8_0)", 823, "medium", true, true);
        // large
        add(l, "ggml-large-v1.bin", "large-v1", 3090, "large", false, false);
        add(l, "ggml-large-v2.bin", "large-v2", 3090, "large", false, false);
        add(l, "ggml-large-v2-q5_0.bin", "large-v2 (q5_0)", 1080, "large", true, false);
        add(l, "ggml-large-v2-q8_0.bin", "large-v2 (q8_0)", 1660, "large", true, false);
        add(l, "ggml-large-v3.bin", "large-v3", 3100, "large", false, false);
        add(l, "ggml-large-v3-q5_0.bin", "large-v3 (q5_0)", 1080, "large", true, false);
        add(l, "ggml-large-v3-turbo.bin", "large-v3-turbo", 1620, "large", false, false);
        add(l, "ggml-large-v3-turbo-q5_0.bin", "large-v3-turbo (q5_0)", 574, "large", true, false);
        add(l, "ggml-large-v3-turbo-q8_0.bin", "large-v3-turbo (q8_0)", 874, "large", true, false);
        ALL = Collections.unmodifiableList(l);
    }

    /** Ordered list of family keys for the accordion UI. */
    public static final String[] FAMILIES = {"tiny", "base", "small", "medium", "large"};

    public static WhisperModel byFileName(String fileName) {
        for (WhisperModel m : ALL) if (m.fileName.equals(fileName)) return m;
        return null;
    }

    public static WhisperModel byId(String id) {
        for (WhisperModel m : ALL) if (m.id.equals(id)) return m;
        return null;
    }

    private WhisperModelCatalog() {}
}
```

- [ ] **Step 4 : Lancer, vérifier le succès**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.voice.WhisperModelCatalogTest"`
Expected: PASS (5 tests).

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/voidterm/voice/WhisperModelCatalog.java app/src/test/java/com/voidterm/voice/WhisperModelCatalogTest.java
git commit -m "feat(whisper): add WhisperModelCatalog (31 ggml variants)"
```

---

### Task 9 : `WhisperModelManager` — état filesystem (voice)

Les modèles whisper vivent dans `{filesDir}/models/` (là où `WhisperBridge` les cherche, et où le file picker copie déjà). Pas de sous-dossier.

**Files:**
- Create: `app/src/main/java/com/voidterm/voice/WhisperModelManager.java`
- Test: `app/src/test/java/com/voidterm/voice/WhisperModelManagerTest.java`

- [ ] **Step 1 : Écrire le test qui échoue (Robolectric, filesystem réel temp)**

```java
package com.voidterm.voice;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class WhisperModelManagerTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
    }

    private void writeModel(String fileName, int bytes) throws IOException {
        File dir = WhisperModelManager.getModelDir(ctx);
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
            out.write(new byte[bytes]);
        }
    }

    @Test
    public void isDownloaded_falseWhenAbsent() {
        assertFalse(WhisperModelManager.isDownloaded(ctx, "ggml-base.bin"));
    }

    @Test
    public void isDownloaded_trueAfterWrite() throws IOException {
        writeModel("ggml-base.bin", 10);
        assertTrue(WhisperModelManager.isDownloaded(ctx, "ggml-base.bin"));
    }

    @Test
    public void isDownloaded_falseForEmptyFile() throws IOException {
        writeModel("ggml-small.bin", 0);
        assertFalse(WhisperModelManager.isDownloaded(ctx, "ggml-small.bin"));
    }

    @Test
    public void listDownloaded_returnsOnlyCatalogModelsPresent() throws IOException {
        writeModel("ggml-base.bin", 10);
        writeModel("ggml-tiny.bin", 10);
        writeModel("custom-user.bin", 10); // not in catalog → ignored
        List<String> ids = WhisperModelManager.listDownloaded(ctx);
        assertEquals(2, ids.size());
        assertTrue(ids.contains("ggml-base.bin"));
        assertTrue(ids.contains("ggml-tiny.bin"));
    }

    @Test
    public void delete_removesFile() throws IOException {
        writeModel("ggml-base.bin", 10);
        assertTrue(WhisperModelManager.delete(ctx, "ggml-base.bin"));
        assertFalse(WhisperModelManager.isDownloaded(ctx, "ggml-base.bin"));
    }

    @Test
    public void nextActiveAfterDelete_returnsRemainingModel() throws IOException {
        writeModel("ggml-base.bin", 10);
        writeModel("ggml-tiny.bin", 10);
        String next = WhisperModelManager.nextActiveAfterDelete(ctx, "ggml-base.bin");
        assertEquals("ggml-tiny.bin", next);
    }

    @Test
    public void nextActiveAfterDelete_returnsNullWhenNoneLeft() throws IOException {
        writeModel("ggml-base.bin", 10);
        assertEquals(null, WhisperModelManager.nextActiveAfterDelete(ctx, "ggml-base.bin"));
    }
}
```

- [ ] **Step 2 : Lancer, vérifier l'échec**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.voice.WhisperModelManagerTest"`
Expected: FAIL — `WhisperModelManager` n'existe pas.

- [ ] **Step 3 : Écrire le manager**

```java
package com.voidterm.voice;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Filesystem state of downloaded Whisper models. They live directly in
 * {filesDir}/models/ — the directory WhisperBridge loads from and the file picker
 * copies into. No HTTP here (the download runs in ModelDownloadService).
 */
public final class WhisperModelManager {

    private static final String MODELS_DIR = "models";

    private WhisperModelManager() {}

    public static File getModelDir(Context context) {
        return new File(context.getFilesDir(), MODELS_DIR);
    }

    /** True if {@code fileName} exists with non-zero size. */
    public static boolean isDownloaded(Context context, String fileName) {
        File f = new File(getModelDir(context), fileName);
        return f.exists() && f.length() > 0;
    }

    /** Catalog models currently present on disk (by fileName). Ignores non-catalog files. */
    public static List<String> listDownloaded(Context context) {
        List<String> present = new ArrayList<>();
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            if (isDownloaded(context, m.fileName)) present.add(m.fileName);
        }
        return present;
    }

    /** Delete a model file. Returns true if a file was removed. */
    public static boolean delete(Context context, String fileName) {
        File f = new File(getModelDir(context), fileName);
        return f.exists() && f.delete();
    }

    /**
     * The fileName that should become active after {@code deletedFileName} is removed:
     * the first remaining downloaded catalog model, or null if none remain.
     */
    public static String nextActiveAfterDelete(Context context, String deletedFileName) {
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            if (m.fileName.equals(deletedFileName)) continue;
            if (isDownloaded(context, m.fileName)) return m.fileName;
        }
        return null;
    }
}
```

- [ ] **Step 4 : Lancer, vérifier le succès**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.voice.WhisperModelManagerTest"`
Expected: PASS (7 tests).

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/voidterm/voice/WhisperModelManager.java app/src/test/java/com/voidterm/voice/WhisperModelManagerTest.java
git commit -m "feat(whisper): add WhisperModelManager (filesystem state)"
```

---

### Task 10 : `WhisperDownloadJob` (app) — policy + bascule moteur

**Files:**
- Create: `app/src/main/java/com/voidterm/app/WhisperDownloadJob.java`

`onComplete` applique l'auto-activation **et** la bascule moteur (la décision-clé du spec).

- [ ] **Step 1 : Écrire le job**

```java
package com.voidterm.app;

import android.content.Context;

import com.voidterm.contracts.DownloadJob;
import com.voidterm.contracts.FileSpec;
import com.voidterm.voice.WhisperModelCatalog;
import com.voidterm.voice.WhisperModelManager;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** DownloadJob policy for ONE Whisper ggml model. Activates it + switches engine to whisper. */
public final class WhisperDownloadJob implements DownloadJob {

    private final Context context;
    private final WhisperModelCatalog.WhisperModel model;

    public WhisperDownloadJob(Context context, WhisperModelCatalog.WhisperModel model) {
        this.context = context.getApplicationContext();
        this.model = model;
    }

    @Override public String id() { return model.fileName; }

    @Override public String displayName() { return "Whisper " + model.displayName; }

    @Override public List<FileSpec> files() {
        File dest = new File(WhisperModelManager.getModelDir(context), model.fileName);
        return Collections.singletonList(new FileSpec(model.url(), dest, model.displayName));
    }

    @Override public void onComplete(Context ctx) {
        // Auto-activate AND switch the engine to whisper (Parakeet is the default; without
        // the engine switch, activating a whisper model would have no visible effect).
        ctx.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SettingsDialog.KEY_MODEL_NAME, model.fileName)
                .putString(SettingsDialog.KEY_TRANSCRIPTION_ENGINE, SettingsDialog.ENGINE_WHISPER)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();
    }
}
```

- [ ] **Step 2 : Compilation**

Run: `./gradlew compileDebugJavaWithJavac`
Expected: PASS.

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/voidterm/app/WhisperDownloadJob.java
git commit -m "feat(whisper): add WhisperDownloadJob (activate + switch engine)"
```

---

### Task 11 : Brancher Whisper dans la factory `DownloadJobs`

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/DownloadJobs.java`
- Modify: `app/src/test/java/com/voidterm/app/DownloadJobsTest.java`

- [ ] **Step 1 : Ajouter le test qui échoue**

Ajouter dans `DownloadJobsTest` :
```java
    @Test
    public void fromIntent_whisperType_returnsWhisperJobForModel() {
        Intent i = new Intent()
                .putExtra(DownloadJobs.EXTRA_JOB_TYPE, DownloadJobs.JOB_WHISPER)
                .putExtra(DownloadJobs.EXTRA_MODEL_ID, "base");
        DownloadJob job = DownloadJobs.fromIntent(RuntimeEnvironment.getApplication(), i);
        assertNotNull(job);
        assertEquals("ggml-base.bin", job.id());
    }

    @Test
    public void fromIntent_whisperUnknownModel_returnsNull() {
        Intent i = new Intent()
                .putExtra(DownloadJobs.EXTRA_JOB_TYPE, DownloadJobs.JOB_WHISPER)
                .putExtra(DownloadJobs.EXTRA_MODEL_ID, "nope");
        assertNull(DownloadJobs.fromIntent(RuntimeEnvironment.getApplication(), i));
    }
```

- [ ] **Step 2 : Lancer, vérifier l'échec**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.app.DownloadJobsTest"`
Expected: FAIL — `JOB_WHISPER` n'existe pas / branche manquante.

- [ ] **Step 3 : Étendre la factory**

Dans `DownloadJobs.java`, ajouter la constante et la branche :
```java
    public static final String JOB_WHISPER = "whisper";
```
Et dans `fromIntent`, avant le `return null` :
```java
        if (JOB_WHISPER.equals(type)) {
            String modelId = intent.getStringExtra(EXTRA_MODEL_ID);
            com.voidterm.voice.WhisperModelCatalog.WhisperModel m =
                    com.voidterm.voice.WhisperModelCatalog.byId(modelId);
            return m == null ? null : new WhisperDownloadJob(context, m);
        }
```
(Remplacer aussi, pour cohérence, la branche parakeet par un test sur `ParakeetDownloadJob.ID` déjà en place.)

- [ ] **Step 4 : Lancer, vérifier le succès**

Run: `./gradlew testDebugUnitTest --tests "com.voidterm.app.DownloadJobsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/voidterm/app/DownloadJobs.java app/src/test/java/com/voidterm/app/DownloadJobsTest.java
git commit -m "feat(whisper): wire WhisperDownloadJob into DownloadJobs factory"
```

---

## PHASE C — UI

### Task 12 : `WhisperCatalogView` — accordéon par famille (app)

Vue dédiée (le `buildModelSection` de `SettingsActivity` fait déjà ~110 lignes ; le catalogue de 31 modèles + états par-ligne justifie sa propre unité — SRP, garde `SettingsActivity` gérable). Logique de construction + refresh par modelId ; les actions remontent via un listener.

**Files:**
- Create: `app/src/main/java/com/voidterm/app/WhisperCatalogView.java`

- [ ] **Step 1 : Écrire la vue**

```java
package com.voidterm.app;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.voidterm.voice.WhisperModelCatalog;
import com.voidterm.voice.WhisperModelManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Accordion catalog of Whisper models, grouped by family. Each model row shows its state
 * (absent / downloaded-inactive / active) and a contextual action. Pure view: it reads the
 * active model name + downloaded state, and reports actions through {@link Listener}.
 */
public class WhisperCatalogView extends LinearLayout {

    public interface Listener {
        void onDownload(WhisperModelCatalog.WhisperModel model);
        void onActivate(WhisperModelCatalog.WhisperModel model);
        void onDelete(WhisperModelCatalog.WhisperModel model);
    }

    private final Listener listener;
    private final int textColor;
    private final int mutedColor;
    // Per-model row action button, keyed by fileName, for targeted progress refresh.
    private final Map<String, Button> actionButtons = new HashMap<>();
    private final Map<String, TextView> stateLabels = new HashMap<>();
    private String activeFileName;
    private boolean downloadInProgress;
    private String downloadingFileName;

    public WhisperCatalogView(Context context, Listener listener, String activeFileName,
                              int textColor, int mutedColor) {
        super(context);
        this.listener = listener;
        this.activeFileName = activeFileName;
        this.textColor = textColor;
        this.mutedColor = mutedColor;
        setOrientation(VERTICAL);
        build();
    }

    private void build() {
        for (String family : WhisperModelCatalog.FAMILIES) {
            addFamily(family);
        }
    }

    private void addFamily(String family) {
        final LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(VERTICAL);
        container.setVisibility(GONE);

        final Button header = new Button(getContext());
        header.setAllCaps(false);
        header.setText("▸ " + family);
        header.setTextColor(textColor);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.setOnClickListener(v -> {
            boolean show = container.getVisibility() != VISIBLE;
            container.setVisibility(show ? VISIBLE : GONE);
            header.setText((show ? "▾ " : "▸ ") + family);
        });
        addView(header);

        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            if (m.family.equals(family)) container.addView(makeRow(m));
        }
        addView(container);
    }

    private View makeRow(WhisperModelCatalog.WhisperModel m) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView name = new TextView(getContext());
        name.setText(m.displayName + "   " + sizeLabel(m.sizeMb));
        name.setTextColor(textColor);
        name.setTextSize(13);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nameLp);
        row.addView(name);

        TextView state = new TextView(getContext());
        state.setTextColor(mutedColor);
        state.setTextSize(12);
        state.setPadding(dp(8), 0, dp(8), 0);
        stateLabels.put(m.fileName, state);
        row.addView(state);

        Button action = new Button(getContext());
        action.setAllCaps(false);
        action.setTextSize(12);
        actionButtons.put(m.fileName, action);
        row.addView(action);

        Button delete = new Button(getContext());
        delete.setAllCaps(false);
        delete.setText("🗑");
        delete.setOnClickListener(v -> listener.onDelete(m));
        row.addView(delete);

        bindRow(m, action, delete);
        return row;
    }

    /** Set the action button label + click + delete visibility from current state. */
    private void bindRow(WhisperModelCatalog.WhisperModel m, Button action, Button delete) {
        boolean downloaded = WhisperModelManager.isDownloaded(getContext(), m.fileName);
        boolean active = m.fileName.equals(activeFileName);
        TextView state = stateLabels.get(m.fileName);

        if (downloadInProgress && m.fileName.equals(downloadingFileName)) {
            action.setText("Downloading…");
            action.setEnabled(false);
            action.setOnClickListener(null);
            if (state != null) state.setText("");
            delete.setVisibility(GONE);
            return;
        }
        action.setEnabled(!downloadInProgress);
        if (!downloaded) {
            action.setText("Download");
            action.setOnClickListener(v -> listener.onDownload(m));
            if (state != null) state.setText("");
            delete.setVisibility(GONE);
        } else if (active) {
            action.setText("✓ Active");
            action.setOnClickListener(null);
            action.setEnabled(false);
            if (state != null) state.setText("");
            delete.setVisibility(VISIBLE);
        } else {
            action.setText("Activate");
            action.setOnClickListener(v -> listener.onActivate(m));
            if (state != null) state.setText("");
            delete.setVisibility(VISIBLE);
        }
    }

    /** Called by SettingsActivity on a whisper download broadcast (progress text on the row). */
    public void onProgress(String fileName, String text) {
        downloadInProgress = true;
        downloadingFileName = fileName;
        TextView state = stateLabels.get(fileName);
        if (state != null) state.setText(text);
        refreshAll();
    }

    /** Download finished (complete or error): re-read disk state, set the new active model. */
    public void onDownloadEnded(String newActiveFileName) {
        downloadInProgress = false;
        downloadingFileName = null;
        if (newActiveFileName != null) activeFileName = newActiveFileName;
        refreshAll();
    }

    public void setActive(String fileName) {
        activeFileName = fileName;
        refreshAll();
    }

    private void refreshAll() {
        for (WhisperModelCatalog.WhisperModel m : WhisperModelCatalog.ALL) {
            Button action = actionButtons.get(m.fileName);
            if (action == null) continue;
            // delete button is the next sibling in the row
            LinearLayout row = (LinearLayout) action.getParent();
            Button delete = (Button) row.getChildAt(row.getChildCount() - 1);
            bindRow(m, action, delete);
        }
    }

    private static String sizeLabel(int mb) {
        return mb >= 1024 ? String.format(java.util.Locale.US, "%.1f GB", mb / 1024f) : mb + " MB";
    }

    private int dp(int v) { return PanelUtils.dp(getContext(), v); }
}
```

- [ ] **Step 2 : Compilation**

Run: `./gradlew compileDebugJavaWithJavac`
Expected: PASS.

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/voidterm/app/WhisperCatalogView.java
git commit -m "feat(whisper): add WhisperCatalogView (accordion catalog UI)"
```

---

### Task 13 : Intégrer le catalogue + router le broadcast dans `SettingsActivity`

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/SettingsActivity.java`

- [ ] **Step 1 : Ajouter un champ pour la vue catalogue**

Près des champs existants (lignes ~92-101) :
```java
    // Whisper catalog view (model section)
    private WhisperCatalogView whisperCatalogView;
```

- [ ] **Step 2 : Construire la vue dans `whisperControls` (dans `buildModelSection`)**

Juste après `whisperControls.addView(browseBtn);` (ligne ~322), ajouter :
```java
        String activeModel = prefs.getString(SettingsDialog.KEY_MODEL_NAME, SettingsDialog.DEFAULT_MODEL);
        whisperCatalogView = new WhisperCatalogView(this, new WhisperCatalogView.Listener() {
            @Override public void onDownload(com.voidterm.voice.WhisperModelCatalog.WhisperModel m) {
                if (ModelDownloadService.isRunning()) return; // one download at a time
                startForegroundService(new Intent(SettingsActivity.this, ModelDownloadService.class)
                        .setAction(ModelDownloadService.ACTION_START_DOWNLOAD)
                        .putExtra(DownloadJobs.EXTRA_JOB_TYPE, DownloadJobs.JOB_WHISPER)
                        .putExtra(DownloadJobs.EXTRA_MODEL_ID, m.id));
                whisperCatalogView.onProgress(m.fileName, "Starting…");
            }
            @Override public void onActivate(com.voidterm.voice.WhisperModelCatalog.WhisperModel m) {
                activateWhisperModel(m.fileName);
            }
            @Override public void onDelete(com.voidterm.voice.WhisperModelCatalog.WhisperModel m) {
                confirmDeleteWhisperModel(m);
            }
        }, activeModel, textColor, mutedColor);
        whisperControls.addView(whisperCatalogView);
```

- [ ] **Step 3 : Ajouter les helpers `activateWhisperModel` + `confirmDeleteWhisperModel`**

Après `onModelFileSelected(...)` (vers ligne ~522) :
```java
    /** Activate a downloaded whisper model: set it active AND switch the engine to whisper. */
    private void activateWhisperModel(String fileName) {
        prefs.edit()
                .putString(SettingsDialog.KEY_MODEL_NAME, fileName)
                .putString(SettingsDialog.KEY_TRANSCRIPTION_ENGINE, SettingsDialog.ENGINE_WHISPER)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();
        if (whisperCatalogView != null) whisperCatalogView.setActive(fileName);
    }

    private void confirmDeleteWhisperModel(com.voidterm.voice.WhisperModelCatalog.WhisperModel m) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + m.displayName + "?")
                .setMessage("Remove " + m.fileName + " (" + m.sizeMb + " MB)?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    String active = prefs.getString(SettingsDialog.KEY_MODEL_NAME,
                            SettingsDialog.DEFAULT_MODEL);
                    com.voidterm.voice.WhisperModelManager.delete(this, m.fileName);
                    if (m.fileName.equals(active)) {
                        String next = com.voidterm.voice.WhisperModelManager
                                .nextActiveAfterDelete(this, m.fileName);
                        prefs.edit().putString(SettingsDialog.KEY_MODEL_NAME,
                                next != null ? next : SettingsDialog.DEFAULT_MODEL).apply();
                        if (whisperCatalogView != null) {
                            whisperCatalogView.setActive(next != null ? next : SettingsDialog.DEFAULT_MODEL);
                        }
                    } else if (whisperCatalogView != null) {
                        whisperCatalogView.setActive(active);
                    }
                })
                .show();
    }
```

- [ ] **Step 4 : Router le broadcast vers la bonne vue**

Dans `onDownloadBroadcast` (ligne ~454), router selon `EXTRA_MODEL_ID` : un id de modèle whisper (présent au catalogue) → la vue catalogue ; sinon (Parakeet) → l'UI Parakeet existante. Remplacer le corps de `onDownloadBroadcast` par :
```java
    private void onDownloadBroadcast(Intent intent) {
        String action = intent.getAction();
        String text = intent.getStringExtra(ModelDownloadService.EXTRA_TEXT);
        String modelId = intent.getStringExtra(ModelDownloadService.EXTRA_MODEL_ID);

        boolean whisper = modelId != null
                && com.voidterm.voice.WhisperModelCatalog.byFileName(modelId) != null;

        if (whisper) {
            if (whisperCatalogView == null) return;
            if (ModelDownloadService.BROADCAST_PROGRESS.equals(action)) {
                whisperCatalogView.onProgress(modelId, text != null ? text : "");
            } else if (ModelDownloadService.BROADCAST_COMPLETE.equals(action)) {
                whisperCatalogView.onDownloadEnded(modelId); // model becomes active
            } else if (ModelDownloadService.BROADCAST_ERROR.equals(action)) {
                whisperCatalogView.onDownloadEnded(null);
            }
            return;
        }

        // Parakeet path (unchanged)
        if (ModelDownloadService.BROADCAST_PROGRESS.equals(action)) {
            applyDownloadUiState(true);
            if (parakeetProgressText != null && text != null) parakeetProgressText.setText(text);
        } else if (ModelDownloadService.BROADCAST_COMPLETE.equals(action)) {
            applyDownloadUiState(false);
            if (parakeetProgressText != null) parakeetProgressText.setText("All models downloaded");
            if (parakeetStatusView != null) updateParakeetStatus(parakeetStatusView, true);
        } else if (ModelDownloadService.BROADCAST_ERROR.equals(action)) {
            applyDownloadUiState(false);
            if (parakeetProgressText != null && text != null) {
                parakeetProgressText.setText("Error: " + text);
            }
        }
    }
```

- [ ] **Step 5 : Build + tests**

Run: `./gradlew assembleDebug && ./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tous les tests passent.

- [ ] **Step 6 : Commit**

```bash
git add app/src/main/java/com/voidterm/app/SettingsActivity.java
git commit -m "feat(whisper): integrate catalog view + route download broadcasts"
```

---

### Task 14 : Aligner le file picker custom sur la bascule moteur

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/SettingsActivity.java`

Choisir un `.bin` custom doit aussi basculer le moteur (sinon sans effet sous Parakeet).

- [ ] **Step 1 : Modifier `onModelFileSelected`**

Dans `onModelFileSelected` (ligne ~509), remplacer :
```java
        prefs.edit()
                .putString(SettingsDialog.KEY_MODEL_NAME, filename)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();
```
par :
```java
        prefs.edit()
                .putString(SettingsDialog.KEY_MODEL_NAME, filename)
                .putString(SettingsDialog.KEY_TRANSCRIPTION_ENGINE, SettingsDialog.ENGINE_WHISPER)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();
```

- [ ] **Step 2 : Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/voidterm/app/SettingsActivity.java
git commit -m "feat(whisper): custom file picker also switches engine to whisper"
```

- [ ] **Step 4 : ⚠️ RE-TEST MANUEL SUR QUEST (Whisper de bout en bout)**

1. Settings → Voice Engine → Whisper. Déplier `base` → `Download` sur `base (q5_1)`.
2. Progression sur la ligne, un seul download à la fois (autres boutons grisés). Notification visible, Cancel OK.
3. À la fin : moteur basculé sur Whisper, modèle actif = celui téléchargé, transcription fonctionnelle.
4. Télécharger un 2ᵉ modèle, vérifier la coexistence ; `Activate` sur l'un, `✓ Active` correct.
5. `🗑` sur l'actif → l'actif retombe sur un modèle restant.

---

## PHASE D — Documentation

### Task 15 : Mettre à jour `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1 : Mettre à jour la section « Parakeet Model Download » → renommer en « Model Download (Parakeet + Whisper) »**

Documenter : `ModelDownloadService` générique + `DownloadJob` (policy) dans `contracts`, `ParakeetDownloadJob`/`WhisperDownloadJob`, `HttpModelDownloader`, `WhisperModelCatalog` (31 variantes), `WhisperModelManager` ({filesDir}/models/), activation Whisper = bascule moteur, broadcast porte `EXTRA_MODEL_ID`. Mentionner que le service garde channel `voidterm_download` + notification id 2.

- [ ] **Step 2 : Mettre à jour la section « Settings & Model Selection »**

Indiquer que les modèles Whisper se téléchargent désormais in-app (catalogue accordéon) en plus du file picker, et que sélectionner un modèle Whisper bascule le moteur sur whisper.

- [ ] **Step 3 : Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): document generalized model download (Parakeet + Whisper)"
```

---

## Self-Review (rempli par l'auteur du plan)

**Couverture spec ↔ tâches :**
- Archi policy/mechanism → Tasks 1-7. `DownloadJob` dans contracts → Task 2. `HttpModelDownloader` → Task 3. ✔
- Catalogue 31 variantes → Task 8 (data vérifiée). ✔
- Coexistence + état → Task 9. ✔
- Activation = bascule moteur → Tasks 10, 13, 14. ✔
- UI accordéon par famille → Tasks 12-13. ✔
- File picker conservé + aligné → Task 14. ✔
- Migration behavior-preserving + re-test Parakeet → Tasks 6-7 (+ re-test manuel Step 6). ✔
- Manifest + constantes même commit → Task 7. ✔
- Tests (catalog/manager/factory/DTO) → Tasks 1, 5, 8, 9, 11. ✔
- Edge case suppression actif → Task 9 (`nextActiveAfterDelete`) + Task 13. ✔

**Cohérence des types :** `DownloadJob.id()/displayName()/files()/onComplete()` identiques partout ; `FileSpec{url,destFile,label}` ; `WhisperModel.id/fileName/url()/sizeMb/family` cohérents Tasks 8→10→13 ; constantes service (`EXTRA_MODEL_ID`, `BROADCAST_*`, `JOB_WHISPER`, `ParakeetDownloadJob.ID`) référencées de façon stable.

**Limite de testabilité assumée :** transfert HTTP, service foreground et vue Android ne sont pas unit-testés (comme l'existant) — couverts par les re-tests manuels Quest (Tasks 7 & 14).
