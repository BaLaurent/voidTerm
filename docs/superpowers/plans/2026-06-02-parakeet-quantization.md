# Sélecteur de quantisation Parakeet v3 (int8/fp32) — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permettre de choisir la quantisation du modèle Parakeet v3 (int8 ~670 Mo / fp32 ~2,5 Go), avec cohabitation et auto-activation, en réutilisant l'architecture de download policy/mechanism.

**Architecture:** `ParakeetQuantization` (catalogue de 2 entrées) devient l'analogue du « modèle » Whisper. `ParakeetModelManager` est paramétré par quantisation, `ParakeetDownloadJob` aussi, `ParakeetEngine` charge les fichiers ONNX de la quantisation active (pref `KEY_PARAKEET_QUANTIZATION`, reload moteur complet). L'UI Parakeet de `SettingsActivity` (statut + 1 bouton download) est remplacée par une vue `ParakeetQuantizationView` à 2 lignes.

**Tech Stack:** Java 17, Android SDK 34, JUnit 4 + Robolectric 4.12. Build : `./gradlew`.

**Spec source :** `docs/superpowers/specs/2026-06-02-parakeet-quantization-design.md`

**Conventions build/test :**
- Compile : `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugJavaWithJavac --console=plain`
- Tests : `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest --console=plain`
- Un test : `... --tests "com.voidterm.voice.ParakeetQuantizationTest"`
- `assembleDebug` (APK/NDK) **non requis** ici — `compileDebugJavaWithJavac` + `testDebugUnitTest` suffisent. Le test device est escaladé à l'utilisateur.

**Branche :** `feature/whisper-model-download` (cette feature étend le code download non encore mergé).

---

## File Structure

**Nouveaux fichiers**
- `app/src/main/java/com/voidterm/voice/ParakeetQuantization.java` — catalogue (2 entrées : INT8, FP32) + fichiers communs + `BASE_URL`.
- `app/src/main/java/com/voidterm/app/ParakeetQuantizationView.java` — vue 2 lignes (analogue plat de `WhisperCatalogView`).
- Tests : `ParakeetQuantizationTest`, `ParakeetModelManagerTest`, et extension de `DownloadJobsTest`.

**Fichiers modifiés**
- `app/src/main/java/com/voidterm/app/SettingsDialog.java` — `KEY_PARAKEET_QUANTIZATION` + valeurs.
- `app/src/main/java/com/voidterm/voice/ParakeetModelManager.java` — paramétré par quantisation ; corriger le commentaire `~534 MB`.
- `app/src/main/java/com/voidterm/app/ParakeetDownloadJob.java` — paramétré (`ID`→`JOB_TYPE`, `id()`=quantisation, `onComplete` écrit la pref).
- `app/src/main/java/com/voidterm/app/DownloadJobs.java` — branche parakeet lit `EXTRA_MODEL_ID`.
- `app/src/main/java/com/voidterm/voice/ParakeetEngine.java` — `loadModel` charge la quantisation active.
- `app/src/main/java/com/voidterm/app/SettingsActivity.java` — démolition UI Parakeet + intégration de la vue.
- `CLAUDE.md` — doc.

**Stratégie compile-green (expand-contract) :** Task 3 ajoute les méthodes paramétrées de `ParakeetModelManager` **en gardant** des wrappers no-arg temporaires (délégant à INT8) pour que les appelants existants compilent. Les wrappers sont supprimés en Task 7, une fois tous les appelants migrés.

---

### Task 1 : Pref de quantisation (SettingsDialog)

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/SettingsDialog.java`

- [ ] **Step 1 : Ajouter les constantes**

Après la ligne `public static final String KEY_PARAKEET_MAX_TOKENS_STEP = "parakeet_max_tokens_step";` (~ligne 116), ajouter :
```java
    // Parakeet quantization selection (int8 vs fp32). NOT a CONFIG_KEY — changing it
    // requires a full engine reload (different ONNX files), via KEY_MODEL_RELOAD_REQUESTED.
    public static final String KEY_PARAKEET_QUANTIZATION = "parakeet_quantization";
    public static final String PARAKEET_QUANT_DEFAULT = "int8";
```

- [ ] **Step 2 : Compiler**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugJavaWithJavac --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/voidterm/app/SettingsDialog.java
git commit -m "feat(parakeet): add KEY_PARAKEET_QUANTIZATION pref"
```

---

### Task 2 : Catalogue `ParakeetQuantization` (voice)

**Files:**
- Create: `app/src/main/java/com/voidterm/voice/ParakeetQuantization.java`
- Test: `app/src/test/java/com/voidterm/voice/ParakeetQuantizationTest.java`

- [ ] **Step 1 : Écrire le test qui échoue**

```java
package com.voidterm.voice;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

public class ParakeetQuantizationTest {

    @Test
    public void hasTwoQuantizations() {
        assertEquals(2, ParakeetQuantization.ALL.size());
    }

    @Test
    public void idsAreUniqueAndKnown() {
        Set<String> ids = new HashSet<>();
        for (ParakeetQuantization q : ParakeetQuantization.ALL) {
            assertTrue(ids.add(q.id));
        }
        assertTrue(ids.contains("int8"));
        assertTrue(ids.contains("fp32"));
    }

    @Test
    public void int8_filesAreInlineNoData() {
        ParakeetQuantization q = ParakeetQuantization.byId("int8");
        assertEquals("encoder-model.int8.onnx", q.encoderFile);
        assertEquals("decoder_joint-model.int8.onnx", q.decoderFile);
        assertEquals(0, q.extraFiles.length);
        assertTrue(q.sizeMb > 0);
    }

    @Test
    public void fp32_hasExternalDataFile() {
        ParakeetQuantization q = ParakeetQuantization.byId("fp32");
        assertEquals("encoder-model.onnx", q.encoderFile);
        assertEquals("decoder_joint-model.onnx", q.decoderFile);
        assertEquals(1, q.extraFiles.length);
        assertEquals("encoder-model.onnx.data", q.extraFiles[0]);
    }

    @Test
    public void allFiles_includesCommonPlusSpecific() {
        ParakeetQuantization q = ParakeetQuantization.byId("int8");
        List<String> all = q.allFiles();
        // common (2) + encoder + decoder + 0 extra = 4
        assertEquals(4, all.size());
        assertTrue(all.contains("nemo128.onnx"));
        assertTrue(all.contains("vocab.txt"));
        assertTrue(all.contains("encoder-model.int8.onnx"));
        assertTrue(all.contains("decoder_joint-model.int8.onnx"));

        // fp32: common (2) + encoder + decoder + 1 extra = 5
        assertEquals(5, ParakeetQuantization.byId("fp32").allFiles().size());
    }

    @Test
    public void url_isBaseUrlPlusFileName() {
        assertEquals(ParakeetQuantization.BASE_URL + "encoder-model.int8.onnx",
                ParakeetQuantization.byId("int8").url("encoder-model.int8.onnx"));
    }

    @Test
    public void byId_unknownReturnsNull() {
        assertNull(ParakeetQuantization.byId("nope"));
    }
}
```

- [ ] **Step 2 : Lancer, vérifier l'échec**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest --tests "com.voidterm.voice.ParakeetQuantizationTest" --console=plain`
Expected: FAIL — `ParakeetQuantization` n'existe pas.

- [ ] **Step 3 : Écrire le catalogue**

```java
package com.voidterm.voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The two downloadable quantizations of NVIDIA Parakeet TDT 0.6b v3 (multilingual),
 * from HuggingFace istupakov/parakeet-tdt-0.6b-v3-onnx. They share the preprocessor
 * (nemo128.onnx) and vocab (vocab.txt); only encoder/decoder differ. int8 stores its
 * weights inline (large .onnx); fp32 stores them in an external .data file.
 */
public final class ParakeetQuantization {

    public static final String BASE_URL =
            "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main/";

    /** Files shared by every quantization (preprocessor + vocab). */
    public static final String[] COMMON_FILES = {"nemo128.onnx", "vocab.txt"};

    public final String id;            // "int8" / "fp32"
    public final String displayName;
    public final int sizeMb;
    public final String encoderFile;
    public final String decoderFile;
    public final String[] extraFiles;  // {} for int8, {encoder-model.onnx.data} for fp32

    private ParakeetQuantization(String id, String displayName, int sizeMb,
                                 String encoderFile, String decoderFile, String[] extraFiles) {
        this.id = id;
        this.displayName = displayName;
        this.sizeMb = sizeMb;
        this.encoderFile = encoderFile;
        this.decoderFile = decoderFile;
        this.extraFiles = extraFiles;
    }

    public static final ParakeetQuantization INT8 = new ParakeetQuantization(
            "int8", "int8 (recommended)", 670,
            "encoder-model.int8.onnx", "decoder_joint-model.int8.onnx", new String[]{});

    public static final ParakeetQuantization FP32 = new ParakeetQuantization(
            "fp32", "fp32 (heavy, ~2.5GB)", 2555,
            "encoder-model.onnx", "decoder_joint-model.onnx",
            new String[]{"encoder-model.onnx.data"});

    public static final List<ParakeetQuantization> ALL =
            Collections.unmodifiableList(Arrays.asList(INT8, FP32));

    /** All files required for this quantization: common + encoder + decoder + extras. */
    public List<String> allFiles() {
        List<String> files = new ArrayList<>(Arrays.asList(COMMON_FILES));
        files.add(encoderFile);
        files.add(decoderFile);
        files.addAll(Arrays.asList(extraFiles));
        return files;
    }

    /** Files specific to this quantization (encoder + decoder + extras), NOT the common ones. */
    public List<String> specificFiles() {
        List<String> files = new ArrayList<>();
        files.add(encoderFile);
        files.add(decoderFile);
        files.addAll(Arrays.asList(extraFiles));
        return files;
    }

    public String url(String fileName) {
        return BASE_URL + fileName;
    }

    public static ParakeetQuantization byId(String id) {
        for (ParakeetQuantization q : ALL) {
            if (q.id.equals(id)) return q;
        }
        return null;
    }
}
```

- [ ] **Step 4 : Lancer, vérifier le succès**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest --tests "com.voidterm.voice.ParakeetQuantizationTest" --console=plain`
Expected: PASS (7 tests).

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/voidterm/voice/ParakeetQuantization.java app/src/test/java/com/voidterm/voice/ParakeetQuantizationTest.java
git commit -m "feat(parakeet): add ParakeetQuantization catalog (int8/fp32)"
```

---

### Task 3 : `ParakeetModelManager` paramétré par quantisation (+ wrappers temporaires)

**Files:**
- Modify: `app/src/main/java/com/voidterm/voice/ParakeetModelManager.java`
- Test: `app/src/test/java/com/voidterm/voice/ParakeetModelManagerTest.java`

But : ajouter les méthodes `(ctx, ParakeetQuantization)` ; corriger le commentaire d'en-tête ; garder des wrappers no-arg **temporaires** (délégant à INT8) pour ne pas casser les appelants existants (`ParakeetDownloadJob`, `ParakeetEngine`, `SettingsActivity`) avant leur migration (Tasks 4/5/7). Retirer `REQUIRED_FILES`/`DOWNLOAD_URLS`/`HF_BASE_URL` (remplacés par `ParakeetQuantization`).

- [ ] **Step 1 : Écrire le test qui échoue (Robolectric, temp dir)**

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ParakeetModelManagerTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
    }

    private void write(String fileName, int bytes) throws IOException {
        File dir = ParakeetModelManager.getModelDir(ctx);
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
            out.write(new byte[bytes]);
        }
    }

    private void writeAll(ParakeetQuantization q) throws IOException {
        for (String f : q.allFiles()) write(f, 10);
    }

    @Test
    public void isModelComplete_falseWhenMissing() {
        assertFalse(ParakeetModelManager.isModelComplete(ctx, ParakeetQuantization.INT8));
    }

    @Test
    public void isModelComplete_trueWhenAllPresent() throws IOException {
        writeAll(ParakeetQuantization.INT8);
        assertTrue(ParakeetModelManager.isModelComplete(ctx, ParakeetQuantization.INT8));
    }

    @Test
    public void isModelComplete_falseWhenAnEmptyFile() throws IOException {
        writeAll(ParakeetQuantization.INT8);
        // overwrite one with empty
        write("encoder-model.int8.onnx", 0);
        assertFalse(ParakeetModelManager.isModelComplete(ctx, ParakeetQuantization.INT8));
    }

    @Test
    public void fileSpecs_countMatchesAllFiles() {
        assertEquals(4, ParakeetModelManager.fileSpecs(ctx, ParakeetQuantization.INT8).size());
        assertEquals(5, ParakeetModelManager.fileSpecs(ctx, ParakeetQuantization.FP32).size());
    }

    @Test
    public void delete_removesSpecificFilesButKeepsCommon() throws IOException {
        writeAll(ParakeetQuantization.INT8);
        ParakeetModelManager.deleteModels(ctx, ParakeetQuantization.INT8);
        File dir = ParakeetModelManager.getModelDir(ctx);
        // specific gone
        assertFalse(new File(dir, "encoder-model.int8.onnx").exists());
        assertFalse(new File(dir, "decoder_joint-model.int8.onnx").exists());
        // common always kept
        assertTrue(new File(dir, "nemo128.onnx").exists());
        assertTrue(new File(dir, "vocab.txt").exists());
    }

    @Test
    public void delete_int8_doesNotTouchFp32Files() throws IOException {
        writeAll(ParakeetQuantization.INT8);
        writeAll(ParakeetQuantization.FP32);
        ParakeetModelManager.deleteModels(ctx, ParakeetQuantization.INT8);
        File dir = ParakeetModelManager.getModelDir(ctx);
        assertTrue(new File(dir, "encoder-model.onnx").exists());
        assertTrue(new File(dir, "encoder-model.onnx.data").exists());
        assertTrue(new File(dir, "decoder_joint-model.onnx").exists());
    }
}
```

- [ ] **Step 2 : Lancer, vérifier l'échec**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest --tests "com.voidterm.voice.ParakeetModelManagerTest" --console=plain`
Expected: FAIL — signatures `(ctx, ParakeetQuantization)` absentes.

- [ ] **Step 3 : Réécrire `ParakeetModelManager.java`**

Remplacer **tout le contenu** par :
```java
package com.voidterm.voice;

import android.content.Context;

import com.voidterm.contracts.FileSpec;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages Parakeet TDT v3 ONNX model files, parameterized by quantization.
 * Models are stored in {filesDir}/models/parakeet/.
 *
 * Two quantizations (see {@link ParakeetQuantization}):
 * - int8 (~670 MB): encoder-model.int8.onnx + decoder_joint-model.int8.onnx (inline weights)
 * - fp32 (~2.55 GB): encoder-model.onnx + encoder-model.onnx.data (external) + decoder_joint-model.onnx
 * Shared by both: nemo128.onnx (preprocessor) + vocab.txt (8193 tokens).
 */
public class ParakeetModelManager {

    private static final String MODELS_DIR = "models";
    private static final String PARAKEET_DIR = "parakeet";

    /** Get the parakeet models directory path. */
    public static File getModelDir(Context context) {
        return new File(new File(context.getFilesDir(), MODELS_DIR), PARAKEET_DIR);
    }

    /** True if all files of {@code q} exist with non-zero size. */
    public static boolean isModelComplete(Context context, ParakeetQuantization q) {
        File modelDir = getModelDir(context);
        if (!modelDir.exists()) return false;
        for (String file : q.allFiles()) {
            File f = new File(modelDir, file);
            if (!f.exists() || f.length() == 0) return false;
        }
        return true;
    }

    /** The files to download for {@code q}, as boundary DTOs. */
    public static List<FileSpec> fileSpecs(Context context, ParakeetQuantization q) {
        File modelDir = getModelDir(context);
        List<FileSpec> specs = new ArrayList<>();
        for (String file : q.allFiles()) {
            specs.add(new FileSpec(q.url(file), new File(modelDir, file), file));
        }
        return specs;
    }

    /** Total size on disk of {@code q}'s files, in bytes. */
    public static long getDownloadedSize(Context context, ParakeetQuantization q) {
        File modelDir = getModelDir(context);
        if (!modelDir.exists()) return 0;
        long total = 0;
        for (String file : q.allFiles()) {
            File f = new File(modelDir, file);
            if (f.exists()) total += f.length();
        }
        return total;
    }

    /**
     * Delete only {@code q}'s SPECIFIC files (encoder/decoder/extra). The common files
     * (nemo128.onnx, vocab.txt) are always kept — the other quantization may share them,
     * and they are tiny (~230 KB). Also cleans up any .tmp leftovers.
     */
    public static void deleteModels(Context context, ParakeetQuantization q) {
        File modelDir = getModelDir(context);
        if (!modelDir.exists()) return;
        for (String file : q.specificFiles()) {
            File f = new File(modelDir, file);
            if (f.exists()) f.delete();
        }
        File[] temps = modelDir.listFiles((dir, name) -> name.endsWith(".tmp"));
        if (temps != null) {
            for (File t : temps) t.delete();
        }
    }

    // --- TEMPORARY no-arg back-compat wrappers (default INT8), removed in Task 7 once
    //     ParakeetEngine, ParakeetDownloadJob and SettingsActivity are all migrated. ---
    public static boolean isModelComplete(Context c) { return isModelComplete(c, ParakeetQuantization.INT8); }
    public static List<FileSpec> fileSpecs(Context c) { return fileSpecs(c, ParakeetQuantization.INT8); }
    public static long getDownloadedSize(Context c) { return getDownloadedSize(c, ParakeetQuantization.INT8); }
    public static void deleteModels(Context c) { deleteModels(c, ParakeetQuantization.INT8); }
}
```

- [ ] **Step 4 : Lancer le test + compiler**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest --tests "com.voidterm.voice.ParakeetModelManagerTest" --console=plain`
Expected: PASS (6 tests).
Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugJavaWithJavac --console=plain`
Expected: BUILD SUCCESSFUL (les wrappers no-arg gardent les appelants existants verts).

- [ ] **Step 5 : Commit**

```bash
git add app/src/main/java/com/voidterm/voice/ParakeetModelManager.java app/src/test/java/com/voidterm/voice/ParakeetModelManagerTest.java
git commit -m "refactor(parakeet): parameterize ParakeetModelManager by quantization

Methods take a ParakeetQuantization; delete keeps the shared nemo128/vocab.
Temporary no-arg wrappers (default int8) keep existing callers compiling until
they migrate (removed in the UI integration task). Fixes the stale ~534MB header."
```

---

### Task 4 : `ParakeetEngine` charge la quantisation active

**Files:**
- Modify: `app/src/main/java/com/voidterm/voice/ParakeetEngine.java`

- [ ] **Step 1 : Dans `loadModel`, lire la quantisation active et charger ses fichiers**

Au début du `try` de la lambda thread (juste avant `if (!ParakeetModelManager.isModelComplete(context))`, ~ligne 144), résoudre la quantisation :
```java
                android.content.SharedPreferences sp = context.getSharedPreferences(
                        SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE);
                ParakeetQuantization quant = ParakeetQuantization.byId(
                        sp.getString(SettingsDialog.KEY_PARAKEET_QUANTIZATION,
                                SettingsDialog.PARAKEET_QUANT_DEFAULT));
                if (quant == null) quant = ParakeetQuantization.INT8;
                final ParakeetQuantization fquant = quant;
```
Remplacer `if (!ParakeetModelManager.isModelComplete(context)) {` par :
```java
                if (!ParakeetModelManager.isModelComplete(context, fquant)) {
```
Remplacer le chargement de l'encodeur (lignes ~178-181) :
```java
                bufLog("Loading encoder: " + fquant.encoderFile);
                start = System.currentTimeMillis();
                encoderSession = env.createSession(
                        new File(modelDir, fquant.encoderFile).getAbsolutePath(), opts);
```
Remplacer le chargement du décodeur (lignes ~185-188) :
```java
                bufLog("Loading decoder: " + fquant.decoderFile);
                start = System.currentTimeMillis();
                decoderSession = env.createSession(
                        new File(modelDir, fquant.decoderFile).getAbsolutePath(), opts);
```
(`nemo128.onnx` et `vocab.txt` restent inchangés — fichiers communs. Le `.data` fp32 est résolu automatiquement par ONNX Runtime, qui le cherche à côté de `encoder-model.onnx`.)

S'assurer que `import com.voidterm.app.SettingsDialog;` est présent (il l'est déjà — `ParakeetEngine` l'utilise). `ParakeetQuantization` est dans le même package `voice`, pas d'import nécessaire.

- [ ] **Step 2 : Compiler**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugJavaWithJavac --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/voidterm/voice/ParakeetEngine.java
git commit -m "feat(parakeet): load the active quantization's ONNX files"
```

---

### Task 5 : `ParakeetDownloadJob` paramétré + factory

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/ParakeetDownloadJob.java`
- Modify: `app/src/main/java/com/voidterm/app/DownloadJobs.java`
- Modify: `app/src/main/java/com/voidterm/app/SettingsActivity.java` (la SEULE référence `ParakeetDownloadJob.ID` du start-intent)
- Test: `app/src/test/java/com/voidterm/app/DownloadJobsTest.java`

- [ ] **Step 1 : Ajouter les tests qui échouent (DownloadJobsTest)**

Append dans la classe `DownloadJobsTest` :
```java
    @Test
    public void fromIntent_parakeetInt8_returnsJobWithInt8Id() {
        Intent i = new Intent()
                .putExtra(DownloadJobs.EXTRA_JOB_TYPE, ParakeetDownloadJob.JOB_TYPE)
                .putExtra(ModelDownloadService.EXTRA_MODEL_ID, "int8");
        DownloadJob job = DownloadJobs.fromIntent(RuntimeEnvironment.getApplication(), i);
        assertNotNull(job);
        assertEquals("int8", job.id());
    }

    @Test
    public void fromIntent_parakeetFp32_returnsJobWithFp32Id() {
        Intent i = new Intent()
                .putExtra(DownloadJobs.EXTRA_JOB_TYPE, ParakeetDownloadJob.JOB_TYPE)
                .putExtra(ModelDownloadService.EXTRA_MODEL_ID, "fp32");
        DownloadJob job = DownloadJobs.fromIntent(RuntimeEnvironment.getApplication(), i);
        assertNotNull(job);
        assertEquals("fp32", job.id());
    }

    @Test
    public void fromIntent_parakeetUnknownQuant_returnsNull() {
        Intent i = new Intent()
                .putExtra(DownloadJobs.EXTRA_JOB_TYPE, ParakeetDownloadJob.JOB_TYPE)
                .putExtra(ModelDownloadService.EXTRA_MODEL_ID, "nope");
        assertNull(DownloadJobs.fromIntent(RuntimeEnvironment.getApplication(), i));
    }
```
Le test existant `fromIntent_parakeetType_returnsParakeetJob` (qui n'envoie pas de quantisation) deviendra obsolète : **le remplacer** par le bloc ci-dessus (supprimer l'ancien test parakeet sans quantisation, car le job parakeet exige désormais une quantisation).

- [ ] **Step 2 : Lancer, vérifier l'échec**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest --tests "com.voidterm.app.DownloadJobsTest" --console=plain`
Expected: FAIL — `JOB_TYPE` / constructeur paramétré absents.

- [ ] **Step 3 : Réécrire `ParakeetDownloadJob.java`**

```java
package com.voidterm.app;

import android.content.Context;

import com.voidterm.contracts.DownloadJob;
import com.voidterm.contracts.FileSpec;
import com.voidterm.voice.ParakeetModelManager;
import com.voidterm.voice.ParakeetQuantization;

import java.util.List;

/** DownloadJob policy for one Parakeet quantization (int8 or fp32). */
public final class ParakeetDownloadJob implements DownloadJob {

    /** Job type for the factory (EXTRA_JOB_TYPE). NOT the instance id() (= quantization id). */
    public static final String JOB_TYPE = "parakeet";

    private final Context context;
    private final ParakeetQuantization quant;

    public ParakeetDownloadJob(Context context, ParakeetQuantization quant) {
        this.context = context.getApplicationContext();
        this.quant = quant;
    }

    @Override public String id() { return quant.id; }

    @Override public String displayName() { return "Parakeet " + quant.displayName; }

    @Override public List<FileSpec> files() {
        return ParakeetModelManager.fileSpecs(context, quant);
    }

    @Override public void onComplete(Context ctx) {
        // Auto-activate the downloaded quantization + full engine reload.
        ctx.getSharedPreferences(SettingsDialog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SettingsDialog.KEY_PARAKEET_QUANTIZATION, quant.id)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();
    }
}
```

- [ ] **Step 4 : Mettre à jour `DownloadJobs.fromIntent`**

Remplacer la branche parakeet existante :
```java
        if (ParakeetDownloadJob.ID.equals(type)) {
            return new ParakeetDownloadJob(context);
        }
```
par :
```java
        if (ParakeetDownloadJob.JOB_TYPE.equals(type)) {
            String qid = intent.getStringExtra(ModelDownloadService.EXTRA_MODEL_ID);
            com.voidterm.voice.ParakeetQuantization q =
                    com.voidterm.voice.ParakeetQuantization.byId(qid);
            return q == null ? null : new ParakeetDownloadJob(context, q);
        }
```

- [ ] **Step 5 : Mettre à jour la référence `ParakeetDownloadJob.ID` dans `SettingsActivity`**

Le bouton de download Parakeet actuel passe `.putExtra(DownloadJobs.EXTRA_JOB_TYPE, ParakeetDownloadJob.ID)` (dans `parakeetDownloadBtn.setOnClickListener`, ~ligne 380). Comme ce bouton est supprimé en Task 7, il suffit ici de le faire compiler : remplacer `ParakeetDownloadJob.ID` par `ParakeetDownloadJob.JOB_TYPE` **et** ajouter `.putExtra(ModelDownloadService.EXTRA_MODEL_ID, SettingsDialog.PARAKEET_QUANT_DEFAULT)` sur le même intent (stopgap valide jusqu'à la démolition Task 7). Faire la même chose pour toute autre occurrence de `ParakeetDownloadJob.ID` trouvée par :
`rg -n "ParakeetDownloadJob.ID" app/src/main/java/com/voidterm/app/SettingsActivity.java`

- [ ] **Step 6 : Lancer les tests + compiler**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest --tests "com.voidterm.app.DownloadJobsTest" --console=plain`
Expected: PASS (parakeet int8/fp32/unknown + whisper inchangés).
Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugJavaWithJavac --console=plain`
Expected: BUILD SUCCESSFUL.
Vérifier : `rg -n "ParakeetDownloadJob.ID" --glob "*.java"` → zéro résultat.

- [ ] **Step 7 : Commit**

```bash
git add app/src/main/java/com/voidterm/app/ParakeetDownloadJob.java app/src/main/java/com/voidterm/app/DownloadJobs.java app/src/main/java/com/voidterm/app/SettingsActivity.java app/src/test/java/com/voidterm/app/DownloadJobsTest.java
git commit -m "feat(parakeet): parameterize ParakeetDownloadJob by quantization (ID->JOB_TYPE)"
```

---

### Task 6 : `ParakeetQuantizationView` (vue 2 lignes)

**Files:**
- Create: `app/src/main/java/com/voidterm/app/ParakeetQuantizationView.java`

Analogue plat de `WhisperCatalogView` : pas d'accordéon, itère sur `ParakeetQuantization.ALL` (2 lignes). Keys = `quant.id`. Même contrat (`Listener` + `onProgress`/`onDownloadEnded`/`setActive`).

- [ ] **Step 1 : Écrire la vue**

```java
package com.voidterm.app;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.voidterm.voice.ParakeetModelManager;
import com.voidterm.voice.ParakeetQuantization;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Two-row selector for the Parakeet quantization (int8 / fp32). Each row shows its state
 * (absent / downloaded-inactive / active) and a contextual action. Pure view: reports
 * actions through {@link Listener}; refreshed by the download broadcast via the controller.
 */
public class ParakeetQuantizationView extends LinearLayout {

    public interface Listener {
        void onDownload(ParakeetQuantization q);
        void onActivate(ParakeetQuantization q);
        void onDelete(ParakeetQuantization q);
    }

    private final Listener listener;
    private final int textColor;
    private final int mutedColor;
    private final Map<String, Button> actionButtons = new HashMap<>();
    private final Map<String, Button> deleteButtons = new HashMap<>();
    private final Map<String, TextView> stateLabels = new HashMap<>();
    private String activeId;
    private boolean downloadInProgress;
    private String downloadingId;

    public ParakeetQuantizationView(Context context, Listener listener, String activeId,
                                    int textColor, int mutedColor) {
        super(context);
        this.listener = listener;
        this.activeId = activeId;
        this.textColor = textColor;
        this.mutedColor = mutedColor;
        setOrientation(VERTICAL);
        for (ParakeetQuantization q : ParakeetQuantization.ALL) {
            addView(makeRow(q));
        }
    }

    private View makeRow(ParakeetQuantization q) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView name = new TextView(getContext());
        name.setText(q.displayName + "   " + sizeLabel(q.sizeMb));
        name.setTextColor(textColor);
        name.setTextSize(13);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView state = new TextView(getContext());
        state.setTextColor(mutedColor);
        state.setTextSize(12);
        state.setPadding(dp(8), 0, dp(8), 0);
        stateLabels.put(q.id, state);
        row.addView(state);

        Button action = new Button(getContext());
        action.setAllCaps(false);
        action.setTextSize(12);
        actionButtons.put(q.id, action);
        row.addView(action);

        Button delete = new Button(getContext());
        delete.setAllCaps(false);
        delete.setText("🗑");
        delete.setOnClickListener(v -> listener.onDelete(q));
        deleteButtons.put(q.id, delete);
        row.addView(delete);

        bindRow(q, action, delete);
        return row;
    }

    private void bindRow(ParakeetQuantization q, Button action, Button delete) {
        boolean downloaded = ParakeetModelManager.isModelComplete(getContext(), q);
        boolean active = q.id.equals(activeId);
        TextView state = stateLabels.get(q.id);

        if (downloadInProgress && q.id.equals(downloadingId)) {
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
            action.setOnClickListener(v -> listener.onDownload(q));
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
            action.setOnClickListener(v -> listener.onActivate(q));
            if (state != null) state.setText("");
            delete.setVisibility(VISIBLE);
        }
    }

    public void onProgress(String quantId, String text) {
        downloadInProgress = true;
        downloadingId = quantId;
        TextView state = stateLabels.get(quantId);
        if (state != null) state.setText(text);
        refreshAll();
    }

    public void onDownloadEnded(String newActiveId) {
        downloadInProgress = false;
        downloadingId = null;
        if (newActiveId != null) activeId = newActiveId;
        refreshAll();
    }

    public void setActive(String quantId) {
        activeId = quantId;
        refreshAll();
    }

    private void refreshAll() {
        for (ParakeetQuantization q : ParakeetQuantization.ALL) {
            Button action = actionButtons.get(q.id);
            Button delete = deleteButtons.get(q.id);
            if (action == null || delete == null) continue;
            bindRow(q, action, delete);
        }
    }

    private static String sizeLabel(int mb) {
        return mb >= 1024 ? String.format(Locale.US, "%.1f GB", mb / 1024f) : mb + " MB";
    }

    private int dp(int v) { return PanelUtils.dp(getContext(), v); }
}
```

- [ ] **Step 2 : Compiler**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugJavaWithJavac --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3 : Commit**

```bash
git add app/src/main/java/com/voidterm/app/ParakeetQuantizationView.java
git commit -m "feat(parakeet): add ParakeetQuantizationView (int8/fp32 selector)"
```

---

### Task 7 : Démolition de l'UI Parakeet + intégration de la vue (SettingsActivity)

**Files:**
- Modify: `app/src/main/java/com/voidterm/app/SettingsActivity.java`
- Modify: `app/src/main/java/com/voidterm/voice/ParakeetModelManager.java` (retirer les wrappers no-arg)

C'est la tâche d'intégration. Lire d'abord les blocs concernés avec leurs numéros de ligne courants (`rg -n "parakeet" app/src/main/java/com/voidterm/app/SettingsActivity.java`).

- [ ] **Step 1 : Supprimer les champs Parakeet, ajouter le champ vue**

Remplacer les 5 champs (actuellement ~lignes 99-103) :
```java
    private Button parakeetDownloadBtn;
    private Button parakeetCancelBtn;
    private Button parakeetDeleteBtn;
    private TextView parakeetProgressText;
    private TextView parakeetStatusView;
```
par :
```java
    private ParakeetQuantizationView parakeetQuantizationView;
```

- [ ] **Step 2 : Réécrire le bloc Parakeet de `buildModelSection`**

Remplacer **tout** le bloc `parakeetControls` actuel (de `LinearLayout parakeetControls = new LinearLayout(this);` jusqu'au `body.addView(parakeetControls);` inclus, ~lignes 355-408 — comprend status/download/progress/cancel/delete et le seeding `if (ModelDownloadService.isRunning())`) par :
```java
        // --- Parakeet quantization selector (int8 / fp32) ---
        LinearLayout parakeetControls = new LinearLayout(this);
        parakeetControls.setOrientation(LinearLayout.VERTICAL);

        parakeetControls.addView(makeLabel("Quantization (modèle 0.6b multilingue v3)"));

        String activeQuant = prefs.getString(SettingsDialog.KEY_PARAKEET_QUANTIZATION,
                SettingsDialog.PARAKEET_QUANT_DEFAULT);
        parakeetQuantizationView = new ParakeetQuantizationView(this,
                new ParakeetQuantizationView.Listener() {
            @Override public void onDownload(com.voidterm.voice.ParakeetQuantization q) {
                if (ModelDownloadService.isRunning()) return; // one download at a time
                startForegroundService(new Intent(SettingsActivity.this, ModelDownloadService.class)
                        .setAction(ModelDownloadService.ACTION_START_DOWNLOAD)
                        .putExtra(DownloadJobs.EXTRA_JOB_TYPE, ParakeetDownloadJob.JOB_TYPE)
                        .putExtra(ModelDownloadService.EXTRA_MODEL_ID, q.id));
                parakeetQuantizationView.onProgress(q.id, "Starting…");
            }
            @Override public void onActivate(com.voidterm.voice.ParakeetQuantization q) {
                activateParakeetQuant(q.id);
            }
            @Override public void onDelete(com.voidterm.voice.ParakeetQuantization q) {
                confirmDeleteParakeetQuant(q);
            }
        }, activeQuant, textColor, mutedColor);
        parakeetControls.addView(parakeetQuantizationView);

        TextView fp32Warning = new TextView(this);
        fp32Warning.setText("⚠ fp32 : très lourd, peut ramer / saturer la mémoire sur Quest 2 (6 Go).");
        fp32Warning.setTextSize(12);
        fp32Warning.setTextColor(0xFFFF9800);
        fp32Warning.setPadding(0, dp(8), 0, dp(8));
        parakeetControls.addView(fp32Warning);

        parakeetControls.setVisibility(isWhisper ? View.GONE : View.VISIBLE);
        body.addView(parakeetControls);
```
Note : la variable `boolean modelsReady = ...` (juste avant l'ancien bloc) n'est plus utilisée → la supprimer. La référence `parakeetControls` dans le listener du spinner d'engine (`parakeetControls.setVisibility(whisper ? ...)`, ~ligne 421) reste valide (le conteneur existe toujours).

- [ ] **Step 3 : Supprimer les méthodes Parakeet obsolètes**

Supprimer entièrement `updateParakeetStatus(...)`, `applyDownloadUiState(...)` et `confirmDeleteModels()` (~lignes 429-477). Ajouter à leur place les deux helpers de la vue :
```java
    /** Activate a downloaded quantization: write the pref + request a full engine reload. */
    private void activateParakeetQuant(String quantId) {
        prefs.edit()
                .putString(SettingsDialog.KEY_PARAKEET_QUANTIZATION, quantId)
                .putBoolean(SettingsDialog.KEY_MODEL_RELOAD_REQUESTED, true)
                .apply();
        if (parakeetQuantizationView != null) parakeetQuantizationView.setActive(quantId);
    }

    private void confirmDeleteParakeetQuant(com.voidterm.voice.ParakeetQuantization q) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Parakeet " + q.displayName + "?")
                .setMessage("Removes the " + q.displayName + " files (" + q.sizeMb + " MB).")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    String active = prefs.getString(SettingsDialog.KEY_PARAKEET_QUANTIZATION,
                            SettingsDialog.PARAKEET_QUANT_DEFAULT);
                    com.voidterm.voice.ParakeetModelManager.deleteModels(this, q);
                    if (q.id.equals(active)) {
                        // Active deleted: fall back to the other downloaded quantization, else int8.
                        String next = SettingsDialog.PARAKEET_QUANT_DEFAULT;
                        for (com.voidterm.voice.ParakeetQuantization other
                                : com.voidterm.voice.ParakeetQuantization.ALL) {
                            if (!other.id.equals(q.id)
                                    && com.voidterm.voice.ParakeetModelManager.isModelComplete(this, other)) {
                                next = other.id;
                                break;
                            }
                        }
                        prefs.edit().putString(SettingsDialog.KEY_PARAKEET_QUANTIZATION, next).apply();
                        if (parakeetQuantizationView != null) parakeetQuantizationView.setActive(next);
                    } else if (parakeetQuantizationView != null) {
                        parakeetQuantizationView.setActive(active);
                    }
                })
                .show();
    }
```

- [ ] **Step 4 : Réécrire la branche Parakeet de `onDownloadBroadcast`**

Dans `onDownloadBroadcast`, la branche « else » (non-whisper, actuellement ~lignes 503-514 utilisant `applyDownloadUiState`/`parakeetProgressText`/`parakeetStatusView`) devient :
```java
        // Parakeet path (quantization id "int8"/"fp32")
        if (parakeetQuantizationView == null) return;
        if (ModelDownloadService.BROADCAST_PROGRESS.equals(action)) {
            parakeetQuantizationView.onProgress(modelId, text != null ? text : "");
        } else if (ModelDownloadService.BROADCAST_COMPLETE.equals(action)) {
            parakeetQuantizationView.onDownloadEnded(modelId); // quantization becomes active
        } else if (ModelDownloadService.BROADCAST_ERROR.equals(action)) {
            parakeetQuantizationView.onDownloadEnded(null);
        }
```
(Le routage whisper-vs-parakeet en tête de méthode — `WhisperModelCatalog.byFileName(modelId) != null` — reste inchangé ; un id `"int8"`/`"fp32"` n'est pas un fileName whisper, donc tombe dans la branche Parakeet.)

- [ ] **Step 5 : Réécrire le seeding Parakeet de `onResume`**

Dans `onResume`, le bloc `if (ModelDownloadService.isRunning()) { ... }` (~lignes 187-196) utilise `applyDownloadUiState` + `parakeetProgressText` (supprimés). Le remplacer par un seeding qui route vers la bonne vue selon `runningJobId()` :
```java
        if (ModelDownloadService.isRunning()) {
            String last = ModelDownloadService.lastProgressText();
            String jobId = ModelDownloadService.runningJobId();
            if (jobId != null) {
                if (WhisperModelCatalog.byFileName(jobId) != null && whisperCatalogView != null) {
                    whisperCatalogView.onProgress(jobId, last != null ? last : "Downloading…");
                } else if (com.voidterm.voice.ParakeetQuantization.byId(jobId) != null
                        && parakeetQuantizationView != null) {
                    parakeetQuantizationView.onProgress(jobId, last != null ? last : "Downloading…");
                }
            }
        }
```

- [ ] **Step 6 : Retirer les wrappers no-arg de `ParakeetModelManager`**

Maintenant que plus aucun appelant n'utilise les méthodes no-arg, supprimer de `ParakeetModelManager` le bloc `// --- TEMPORARY no-arg back-compat wrappers ... ---` (les 4 méthodes `isModelComplete(Context)`, `fileSpecs(Context)`, `getDownloadedSize(Context)`, `deleteModels(Context)`).
Vérifier : `rg -n "ParakeetModelManager\.(isModelComplete|fileSpecs|getDownloadedSize|deleteModels)\(this\)|\(context\)" --glob "*.java"` ne doit montrer aucun appel no-arg restant (tous passent une quantisation).

- [ ] **Step 7 : Compiler + tests + scan code mort**

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew compileDebugJavaWithJavac --console=plain`
Expected: BUILD SUCCESSFUL (zéro référence aux champs/méthodes supprimés).
Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest --console=plain`
Expected: tous les tests passent.
Run: `rg -n "parakeetDownloadBtn|parakeetCancelBtn|parakeetDeleteBtn|parakeetProgressText|parakeetStatusView|updateParakeetStatus|applyDownloadUiState|confirmDeleteModels" --glob "*.java"`
Expected: zéro résultat.

- [ ] **Step 8 : Commit**

```bash
git add -A
git commit -m "feat(parakeet): replace Parakeet UI with int8/fp32 quantization selector

Demolishes the old single-model Parakeet UI (status + download/cancel/delete buttons +
updateParakeetStatus/applyDownloadUiState/confirmDeleteModels), wires ParakeetQuantizationView,
routes the Parakeet download broadcast + onResume seeding to it, and removes the temporary
no-arg ParakeetModelManager wrappers."
```

---

### Task 8 : Documentation CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1 : Mettre à jour la section download + Parakeet**

Dans la section « Model Download (Parakeet + Whisper, background) » et/ou la sous-section Parakeet : documenter que Parakeet v3 se décline désormais en 2 quantisations sélectionnables (int8 ~670 Mo / fp32 ~2,5 Go) via `ParakeetQuantization`, que `ParakeetModelManager`/`ParakeetDownloadJob` sont paramétrés par quantisation, que `ParakeetEngine` charge les fichiers de la quantisation active (pref `KEY_PARAKEET_QUANTIZATION`, reload moteur complet — pas un `CONFIG_KEY`), que fp32 ajoute un fichier external `.data` de 2,44 Go résolu par ONNX Runtime, et que l'UI est `ParakeetQuantizationView` (2 lignes, cohabitation + auto-activation). Mentionner le risque OOM fp32 sur Quest 2. Ajouter les nouvelles classes au Package Layout (`ParakeetQuantization` dans `voice`, `ParakeetQuantizationView` dans `app`).

- [ ] **Step 2 : Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): document Parakeet int8/fp32 quantization selector"
```

---

## Self-Review (auteur du plan)

**Couverture spec ↔ tâches :**
- `ParakeetQuantization` catalog → Task 2 ✔ ; tailles réelles (670/2555) ✔.
- `ParakeetModelManager` paramétré, delete garde communs, commentaire 534 corrigé → Task 3 ✔.
- `ParakeetEngine` charge la quantisation active (filenames + isModelComplete) → Task 4 ✔.
- `ParakeetDownloadJob` paramétré (ID→JOB_TYPE, id()=quant, onComplete pref) + factory → Task 5 ✔.
- pref `KEY_PARAKEET_QUANTIZATION` (pas CONFIG_KEYS) → Task 1 + Task 5 onComplete ✔.
- `ParakeetQuantizationView` 2 lignes → Task 6 ✔.
- Démolition UI énumérée + onDownloadBroadcast + onResume seeding → Task 7 ✔.
- Cohabitation + auto-activation + fallback delete actif → Task 5/7 ✔.
- Tests catalog/manager/factory → Tasks 2/3/5 ✔.

**Cohérence des types :** `ParakeetQuantization{id, displayName, sizeMb, encoderFile, decoderFile, extraFiles, allFiles(), specificFiles(), url(), byId(), ALL, INT8, FP32}` cohérent Tasks 2→3→4→5→6→7. `ParakeetDownloadJob.JOB_TYPE` + ctor `(Context, ParakeetQuantization)` cohérent Tasks 5→7. `ParakeetQuantizationView` API (`Listener`, `onProgress`/`onDownloadEnded`/`setActive`) cohérent Tasks 6→7.

**Compile-green :** expand-contract via wrappers no-arg temporaires (Task 3) retirés en Task 7 — chaque commit compile.

**Limite testabilité :** `ParakeetEngine.loadModel` (ONNX), la vue et le service ne sont pas unit-testés (comme l'existant) — couverts par le re-test manuel Quest (fp32 external-data + OOM + non-régression int8), escaladé à l'utilisateur.
