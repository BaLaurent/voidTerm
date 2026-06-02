# Sélecteur de quantisation Parakeet v3 (int8 / fp32)

**Date :** 2026-06-02
**Statut :** Design validé, prêt pour le plan d'implémentation

## Objectif

Permettre de choisir la **quantisation** du modèle Parakeet TDT 0.6b v3 (multilingue) :
- **int8** (~670 Mo) — la quantisation actuelle, rapide, viable sur Quest.
- **fp32** (pleine précision, ~2,5 Go) — légèrement plus précise, nettement plus lourde/lente.

Tailles réelles (vérifiées HF, 2026-06-02) : int8 = `encoder-model.int8.onnx` 652 Mo +
`decoder_joint-model.int8.onnx` 18 Mo + communs ≈ **670 Mo**. fp32 = `encoder-model.onnx`
42 Mo + `encoder-model.onnx.data` 2,44 Go + `decoder_joint-model.onnx` 72 Mo + communs
≈ **2,55 Go**. (L'int8 stocke ses poids *inline* — d'où le gros `.onnx` ; le fp32 les met
dans le `.data` — d'où le petit `.onnx`. La string « 534 MB » du code Parakeet existant est
**fausse** et doit être corrigée.)

Parakeet v3 est un **modèle unique** (0.6b) : il n'existe pas de tailles tiny/base/large
comme Whisper. La seule variante réelle est le niveau de quantisation. On réutilise
l'architecture de download policy/mechanism construite pour Whisper.

Contexte vérifié (HuggingFace `istupakov/parakeet-tdt-0.6b-v3-onnx`, 2026-06-02) :
le repo contient à la fois les fichiers int8 (`*.int8.onnx`) et fp32 (`*.onnx` +
un fichier external `encoder-model.onnx.data` de 2,44 Go). `nemo128.onnx` et `vocab.txt`
sont communs aux deux. Les variantes v2/CTC/RNN-T sont **exclues** : le décodeur TDT actuel
a `BLANK_ID=8192`/`VOCAB_SIZE=8193` codés en dur (vocab multilingue v3), incompatibles
avec un autre vocab sans réécriture.

## Décisions de cadrage (verrouillées)

| Sujet | Décision |
|---|---|
| Portée | Parakeet **v3 uniquement**, 2 quantisations : int8, fp32. |
| Cohabitation | int8 et fp32 peuvent coexister dans `{filesDir}/models/parakeet/` (noms de fichiers distincts ; `nemo128`/`vocab` communs). |
| Activation | Télécharger une quantisation l'**auto-active** (écrit `KEY_PARAKEET_QUANTIZATION` + reload). Activer manuellement une quantisation déjà présente fait de même. |
| UI | 2 lignes dédiées (int8 / fp32) dans la section Parakeet, état + action contextuelle + 🗑, façon `WhisperCatalogView` mais sans accordéon. |
| Architecture | Réutilise le download policy/mechanism (`ModelDownloadService` + `DownloadJob`). |
| Défaut | `int8` — préserve l'existant sans migration destructive. |

## Architecture

La quantisation Parakeet devient l'analogue du « modèle » Whisper.

### Modules

| Module | Package | Type | Rôle |
|---|---|---|---|
| `ParakeetQuantization` | `com.voidterm.voice` | data | Catalogue de 2 entrées (analogue de `WhisperModelCatalog`). Champs : `id`, `displayName`, `sizeMb`, `encoderFile`, `decoderFile`, `extraFiles` (le `.data` pour fp32). Constantes des fichiers communs (`nemo128.onnx`, `vocab.txt`) + `BASE_URL`. `byId(String)`, `ALL`. |
| `ParakeetModelManager` | `com.voidterm.voice` | logique | Refactor : méthodes paramétrées par quantisation — `isModelComplete(ctx, q)`, `fileSpecs(ctx, q)`, `getDownloadedSize(ctx, q)`, `deleteModels(ctx, q)`. `deleteModels(ctx, q)` supprime **uniquement** les fichiers spécifiques à `q` (encoder/decoder/extra) ; les fichiers communs `nemo128`/`vocab` (~230 ko) sont **toujours conservés** (orphelins inoffensifs — évite un edge-case conditionnel, cf. YAGNI). `getModelDir` inchangé. |
| `ParakeetDownloadJob` | `com.voidterm.app` | policy | Refactor : paramétré par `ParakeetQuantization`. La constante de type de job est renommée `ID` → **`JOB_TYPE`** (= `"parakeet"`, branche factory) pour éviter la confusion avec `id()` qui renvoie désormais la quantisation (`"int8"`/`"fp32"`). `onComplete` écrit `KEY_PARAKEET_QUANTIZATION` + `KEY_MODEL_RELOAD_REQUESTED` (reload moteur complet — **pas** une clé de `ParakeetConfig.CONFIG_KEYS`, qui n'invaliderait que le cache config sans recharger les sessions ONNX). |
| `DownloadJobs` | `com.voidterm.app` | factory | La branche `"parakeet"` lit `EXTRA_MODEL_ID` = quantisation id → `ParakeetDownloadJob(ctx, ParakeetQuantization.byId(id))` (null si inconnue). |
| `ParakeetEngine` | `com.voidterm.voice` | moteur | `loadModel` lit `KEY_PARAKEET_QUANTIZATION` (défaut `int8`) et charge `encoderFile`/`decoderFile` de cette quantisation au lieu des `*.int8.onnx` en dur. `nemo128`/`vocab` inchangés. Le `.data` fp32 est résolu automatiquement par ONNX Runtime (même dossier). `isModelLoaded`/erreur "not downloaded" via le manager pour la quantisation active. |
| `ParakeetQuantizationView` | `com.voidterm.app` | UI | Vue dédiée : 2 lignes plates (int8 / fp32), `Listener{onDownload,onActivate,onDelete}` + `onProgress`/`onDownloadEnded`/`setActive`. Remplace le bloc Parakeet actuel dans `SettingsActivity.buildModelSection`. |
| `SettingsDialog` | `com.voidterm.app` | constantes | `KEY_PARAKEET_QUANTIZATION = "parakeet_quantization"` (défaut `"int8"`). |

### Modèle de données `ParakeetQuantization`

| id | displayName | encoderFile | decoderFile | extraFiles | sizeMb |
|---|---|---|---|---|---|
| `int8` | "int8 (recommandé)" | `encoder-model.int8.onnx` | `decoder_joint-model.int8.onnx` | — | 670 |
| `fp32` | "fp32 (lourd, ~2,5 Go)" | `encoder-model.onnx` | `decoder_joint-model.onnx` | `encoder-model.onnx.data` | 2555 |

`extraFiles` ne contient que le `.data` de l'**encodeur** : `decoder_joint-model.onnx`
(72 Mo) est self-contained (pas de `.data`).

Fichiers communs (toujours requis, partagés) : `nemo128.onnx`, `vocab.txt`.
`fileSpecs(ctx, q)` = communs + `encoderFile` + `decoderFile` + `extraFiles`.
Donc int8 = 4 fichiers, fp32 = 5 fichiers. `BASE_URL` =
`https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main/`.

### Conformité aux règles

- `ParakeetQuantization` est l'analogue exact de `WhisperModelCatalog` (data pure).
- `ParakeetDownloadJob` reste la même policy, désormais paramétrée — pas de nouvelle
  abstraction. Le mécanisme (`ModelDownloadService`, `HttpModelDownloader`) est inchangé.
- Pas de vue partagée Whisper/Parakeet (approche 3 rejetée) : `WhisperCatalogView` a une
  logique accordéon-par-famille spécifique ; abstraire pour 2 usages dissemblables serait
  prématuré. `ParakeetQuantizationView` est une vue simple dédiée.

## UI Settings — section Parakeet

```
┌─ Voice Engine : Parakeet ───────────────────┐
│ Quantization (modèle 0.6b multilingue v3)    │
│  int8 (recommandé)   670 MB    ✓ Actif    🗑  │
│  fp32 (lourd)        2.5 GB    [ Download ]   │
│  ⚠ fp32 : très lourd, peut ramer/OOM sur     │
│     Quest 2 (6 Go RAM)                        │
│  [ Annuler ]   ← visible pendant un download  │
└──────────────────────────────────────────────┘
```

États par ligne :
- **absent** → `[ Download ]` (taille affichée)
- **téléchargé, inactif** → `[ Activer ]` + 🗑
- **téléchargé, actif** → `✓ Actif` + 🗑

Pendant un download : la ligne ciblée affiche la progression ; les autres `Download`
grisés (un seul download à la fois). Un avertissement statique signale le coût du fp32.

## Flux de données & cycle de vie

### Download (auto-activation)

1. Tap `Download` sur fp32 → guard `ModelDownloadService.isRunning()`.
2. `startForegroundService(ModelDownloadService, ACTION_START, EXTRA_JOB_TYPE=parakeet, EXTRA_MODEL_ID=fp32)`.
3. `DownloadJobs.fromIntent` → `ParakeetDownloadJob(ctx, FP32)`. `files()` = `fileSpecs(ctx, FP32)` ;
   les fichiers communs déjà présents (depuis int8) sont **skippés** par le runner.
4. Broadcast `PROGRESS` avec `EXTRA_MODEL_ID="fp32"`.
5. `onComplete` → écrit `KEY_PARAKEET_QUANTIZATION="fp32"` + `KEY_MODEL_RELOAD_REQUESTED=true`.
6. Routage `onDownloadBroadcast` : `WhisperModelCatalog.byFileName("fp32")==null` → branche
   Parakeet → `ParakeetQuantizationView.onProgress("fp32", …)` puis `onDownloadEnded("fp32")`.

### Activation manuelle

`[ Activer ]` sur une quantisation déjà téléchargée → écrit `KEY_PARAKEET_QUANTIZATION` +
reload → `setActive`.

### Suppression

🗑 → dialog → `ParakeetModelManager.deleteModels(ctx, q)` : supprime `encoderFile` +
`decoderFile` + `extraFiles` de `q`. Les communs (`nemo128`/`vocab`, ~230 ko) sont **toujours
conservés** — pas de logique conditionnelle (supprimer int8 ne touche jamais aux fichiers que
fp32 partage). Si on supprime la quantisation **active** → fallback : activer l'autre
quantisation si téléchargée, sinon revenir au défaut `int8` (qui sera « absent » jusqu'à
re-download).

### Concurrence

Un seul download global (flag `running` du service), partagé avec Whisper et l'autre
quantisation. Boutons `Download` grisés sinon.

## Gestion d'erreur

Pattern réutilisé : sentinel `CANCELLED`, cleanup `.tmp`, broadcast `ERROR`, teardown
silencieux à l'annulation. fp32 interrompu → restart from scratch (pas de resume partiel),
cohérent avec l'existant. Le `.data` external (2,44 Go) suit le même chemin `.tmp`+rename.

## Risques

- **fp32 OOM sur Quest 2 (6 Go RAM)** : charger ~2,44 Go d'encodeur fp32 + l'overhead ONNX
  peut dépasser la mémoire dispo. Pas de garde dure (choix produit), mais avertissement UI
  explicite. À valider au re-test manuel Quest.
- **fp32 inference plus lente** : attendu ; documenté dans le displayName + warning.

## Migration

Zéro casse. Les utilisateurs actuels ont déjà les fichiers int8 dans
`{filesDir}/models/parakeet/`. Le défaut `KEY_PARAKEET_QUANTIZATION="int8"` les garde
actifs ; `isModelComplete(ctx, INT8)` reste vrai. `ParakeetDownloadJob.ID` reste `"parakeet"`
comme `EXTRA_JOB_TYPE` (seul `job.id()` passe de `"parakeet"` à la quantisation id).

## Tests

- `ParakeetQuantizationTest` (pur) : 2 entrées, ids uniques, int8 a `*.int8.onnx` (pas de
  `.data`), fp32 a `*.onnx` + `encoder-model.onnx.data`, fichiers communs corrects, URLs
  cohérentes (`BASE_URL` + fileName).
- `ParakeetModelManagerTest` (Robolectric, temp dir) : `isModelComplete(q)` (true seulement
  si tous les fichiers de `q` présents non-vides) ; `fileSpecs(q)` (4 int8 / 5 fp32) ;
  `deleteModels(INT8)` supprime les fichiers spécifiques int8 mais **conserve toujours
  `nemo128`/`vocab`** et ne touche pas aux fichiers fp32 présents ; `getDownloadedSize(q)`.
- `DownloadJobsTest` : `parakeet`+`int8` → job `id()=="int8"` ; `parakeet`+`fp32` →
  `"fp32"` ; `parakeet`+quantisation inconnue → null.
- Re-test manuel Quest (escaladé) : (a) download fp32 réel ; (b) **fp32 charge sans erreur
  d'external-data** — ORT doit résoudre `encoder-model.onnx.data` relatif au `.onnx` via
  `getAbsolutePath()` ; vérifier l'absence de `FileNotFound`/erreur onnxruntime external-data ;
  (c) comportement OOM/latence sur le device ; (d) non-régression int8 (toujours actif par défaut).
