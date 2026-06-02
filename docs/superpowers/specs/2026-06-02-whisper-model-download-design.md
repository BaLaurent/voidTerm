# Téléchargement in-app des modèles Whisper (ggml)

**Date :** 2026-06-02
**Statut :** Design validé, prêt pour le plan d'implémentation

## Objectif

Offrir le téléchargement intégré des modèles Whisper (ggml) depuis l'écran Settings,
à l'image du système déjà en place pour Parakeet. Aujourd'hui, choisir un modèle
Whisper passe uniquement par un file picker (`ACTION_OPEN_DOCUMENT`) : l'utilisateur
doit se procurer le `.bin` lui-même. On veut un catalogue téléchargeable in-app, avec
plusieurs modèles coexistant et un modèle actif sélectionnable.

Contexte : depuis ce cycle, **Parakeet est le moteur par défaut** (`SettingsDialog.ENGINE_DEFAULT`).
Cela a une conséquence directe sur l'activation d'un modèle Whisper (voir « Activation »).

## Décisions de cadrage (verrouillées)

| Sujet | Décision |
|---|---|
| Catalogue | **Toutes** les variantes ggml du repo HuggingFace `ggerganov/whisper.cpp` (31 fichiers, voir annexe). |
| Coexistence | Plusieurs modèles téléchargés coexistent dans `{filesDir}/models/`. |
| File picker | **Conservé** pour les modèles custom hors catalogue. |
| Activation | Activer un modèle Whisper (au download ou via le bouton) **bascule aussi le moteur sur Whisper** (`KEY_TRANSCRIPTION_ENGINE=whisper`). Auto-activation systématique après un download réussi. |
| UI liste | Catalogue présenté en **accordéon par famille de taille** (tiny / base / small / medium / large), réutilisant le pattern de sections repliables de `SettingsActivity`. |
| Architecture | **Approche policy/mechanism** : généraliser le service de download. |

## Architecture

On casse l'actuel `ParakeetDownloadService` (qui mélange mécanisme et policy) en un
**mécanisme générique** plus des **policies par moteur**. C'est le pattern
policy/mechanism du CLAUDE.md : le service décide *comment* télécharger en foreground,
le `DownloadJob` décide *quoi* télécharger.

### Modules

| Module | Package | Type | Rôle |
|---|---|---|---|
| `HttpModelDownloader` | `com.voidterm.voice` | mechanism | Transfert HTTP HuggingFace bas-niveau : redirections, throttle 200 ms, écriture `.tmp` + rename atomique, annulation coopérative. **Extrait tel quel** de l'actuel `ParakeetModelManager.downloadFile()` — c'est la seule vraie connaissance dupliquée (le protocole HF), partagée par les deux moteurs. |
| `DownloadJob` *(interface)* | `com.voidterm.contracts` | policy | Décrit le travail : `String id()`, `String displayName()`, `List<FileSpec> files()`, `void onComplete(Context)`. |
| `FileSpec` *(record/struct)* | `com.voidterm.contracts` | DTO | `{ String url, File destFile, String label }` — la donnée plain-data qui traverse la boundary. |
| `ModelDownloadService` | `com.voidterm.app` | mechanism | **Remplace** `ParakeetDownloadService`. Foreground + wakelock + notification + broadcast + cancel. Exécute un `DownloadJob` reconstruit depuis l'Intent. Un seul download à la fois (flag `running` global conservé). |
| `DownloadJobs.fromIntent()` *(factory)* | `com.voidterm.app` | — | Reconstruit le bon job depuis l'Intent (`EXTRA_JOB_TYPE` + `EXTRA_MODEL_ID`). Un `Service` reçoit des Intents, pas des objets — d'où la factory. |
| `ParakeetDownloadJob` | `com.voidterm.app` | policy | Implémente `DownloadJob` : les 4 fichiers ONNX fixes. `onComplete` = `KEY_MODEL_RELOAD_REQUESTED=true` (comportement actuel préservé). |
| `WhisperModelCatalog` | `com.voidterm.voice` | data | Liste immuable des `WhisperModel{ id, fileName, displayName, sizeBytes, url, family, quantized, englishOnly }`. Source de vérité du catalogue (annexe). |
| `WhisperDownloadJob` | `com.voidterm.app` | policy | Implémente `DownloadJob` pour **un** `WhisperModel`. `onComplete` écrit `KEY_MODEL_NAME` + `KEY_TRANSCRIPTION_ENGINE=whisper` + `KEY_MODEL_RELOAD_REQUESTED=true` (auto-activation + bascule moteur). |
| `WhisperModelManager` | `com.voidterm.voice` | logique | État sur le filesystem : `isDownloaded(id)`, `listDownloaded()`, `delete(id)`, `getModelDir()`. **Pas d'HTTP** (délégué au service + job). |

### Note sur les règles d'abstraction

- `DownloadJob` est une interface à une **boundary platform-service**, avec **2
  implémentations concrètes** (`ParakeetDownloadJob`, `WhisperDownloadJob`) : justifiée,
  pas spéculative.
- `DownloadJob` porte du comportement (`onComplete`) : ce n'est **pas** une violation de
  la règle « data crossing boundaries MUST be plain data ». C'est une **strategy**
  (composition over inheritance, prônée par le CLAUDE.md). Le DTO plain-data est `FileSpec`.
- `HttpModelDownloader` extrait la seule connaissance réellement partagée. Le reste du
  squelette service n'est pas dupliqué : il est unifié dans `ModelDownloadService`.

## UI Settings — section « Whisper Model »

Accordéon par famille, chaque modèle = une ligne avec son état et une action contextuelle.

```
┌─ Whisper Model ─────────────────────────────┐
│ Actif : ggml-base.bin                        │
│ [ Choisir un fichier… ]   ← file picker gardé│
│  ⚠ medium/large sont lents en VR (Quest)     │
│                                              │
│  ▸ tiny                                      │
│  ▾ base                                      │
│     base           148 MB    ✓ Actif         │
│     base.en        148 MB    [ Activer ] 🗑    │
│     base q5_1       60 MB    [ Télécharger ]  │
│     base q8_0       82 MB    [ Télécharger ]  │
│  ▸ small                                     │
│  ▸ medium                                    │
│  ▸ large                                     │
└──────────────────────────────────────────────┘
```

### Trois états par modèle

- **absent** → `[ Télécharger ]` (taille affichée)
- **téléchargé, inactif** → `[ Activer ]` + `🗑`
- **téléchargé, actif** → badge `✓ Actif` (non cliquable) + `🗑`

### Pendant un download

La ligne ciblée passe en `[ Téléchargement… 45 % ]` + `Annuler`, pilotée par le broadcast
existant **étendu avec `EXTRA_MODEL_ID`** pour cibler la bonne ligne. Les autres boutons
`Télécharger` se grisent (un seul download à la fois, Parakeet inclus).

## Flux de données & cycle de vie

### Download (auto-activation + bascule moteur)

1. User déplie `small` → `[ Télécharger ]`.
2. `startForegroundService(ModelDownloadService, ACTION_START, EXTRA_JOB_TYPE=whisper, EXTRA_MODEL_ID=small)`.
3. Service : `DownloadJobs.fromIntent()` → `WhisperDownloadJob(small)`, `running` CAS, foreground + wakelock.
4. Transfert via `HttpModelDownloader` → broadcast `PROGRESS` avec `EXTRA_MODEL_ID=small`.
5. `onComplete` → `WhisperDownloadJob.onComplete()` écrit `KEY_MODEL_NAME="ggml-small.bin"`,
   `KEY_TRANSCRIPTION_ENGINE="whisper"`, `KEY_MODEL_RELOAD_REQUESTED=true`.
6. Retour Settings → reload via le mécanisme existant (`KEY_MODEL_RELOAD_REQUESTED`),
   et rebuild de la section transcription (déjà déclenché sur engine switch).

### Activation manuelle (modèle déjà présent)

`[ Activer ]` → écrit `KEY_MODEL_NAME` + `KEY_TRANSCRIPTION_ENGINE=whisper` +
`KEY_MODEL_RELOAD_REQUESTED=true` → reload. Refresh des badges.

**Cohérence du file picker :** sélectionner un `.bin` custom via le file picker
`ACTION_OPEN_DOCUMENT` suit désormais la **même** règle — il bascule aussi
`KEY_TRANSCRIPTION_ENGINE=whisper` (sinon choisir un modèle Whisper custom resterait
sans effet sous le moteur Parakeet par défaut). C'est un alignement du chemin picker
existant sur la nouvelle sémantique « choisir un modèle Whisper = utiliser Whisper ».

### Suppression

`🗑` → dialog de confirmation → `WhisperModelManager.delete(id)`.
**Edge case :** si on supprime le modèle actif, l'actif retombe sur le premier modèle
restant téléchargé ; s'il n'en reste aucun, `KEY_MODEL_NAME` revient au défaut
(`ggml-base.bin`, qui sera alors « absent » jusqu'à un nouveau download).

### Concurrence

Un seul download global (flag `running`). Si Parakeet **ou** un Whisper tourne déjà, les
boutons `Télécharger` sont grisés et le service rejette le nouvel ordre (comportement
actuel). Bénéfice de l'unification : Parakeet et Whisper partagent désormais la même
garantie d'exclusion.

## Gestion d'erreur

Pattern Parakeet réutilisé sans changement :

- Sentinel `CANCELLED`, cleanup du `.tmp`, broadcast `ERROR`, teardown silencieux à l'annulation.
- Échec HTTP → `.tmp` supprimé, le modèle reste « absent », notification d'erreur.
- Pas de resume partiel (restart from scratch), cohérent avec l'existant.

## Migration (approche 2 — touche du code stable)

`ParakeetDownloadService` n'a **aucun test automatisé** (« integration testing is manual on
Quest »). La généralisation touche ce code stable sans filet, d'où des garde-fous explicites :

1. **Même commit :** renommage `ParakeetDownloadService` → `ModelDownloadService`,
   `AndroidManifest.xml` (déclaration `<service>`), toutes les constantes publiques
   (`BROADCAST_PROGRESS/COMPLETE/ERROR`, `ACTION_START_DOWNLOAD`, `ACTION_CANCEL_DOWNLOAD`,
   `EXTRA_*`, ID de notification **2**, channel `voidterm_download`), et `SettingsActivity`
   (seul autre consommateur) migrent **ensemble**.
2. **Behavior-preserving (critère d'acceptation) :** mêmes channel id, ID notif 2, action
   strings et extras de broadcast qu'aujourd'hui pour le chemin Parakeet.
3. **Re-test manuel obligatoire :** le download Parakeet complet est re-testé sur Quest
   après le refactor (couvre le risque principal de l'approche 2).

## Tests

Style du repo : les classes pures sont testées en JUnit/Robolectric ; l'intégration est manuelle.

- `WhisperModelCatalogTest` (pur) : ids uniques, URLs/fileNames cohérents avec le schéma,
  `sizeBytes > 0`, familles bien réparties.
- `DownloadJobsFactoryTest` (pur) : round-trip Intent → bon job (`EXTRA_JOB_TYPE` +
  `EXTRA_MODEL_ID` → bonne implémentation et bon modèle).
- `WhisperModelManagerTest` (Robolectric / temp dir) : `isDownloaded` / `listDownloaded` /
  `delete`, et le fallback d'actif après suppression du modèle actif.
- `ModelDownloadService` / chemin Parakeet : re-test manuel sur Quest (voir Migration).

## Annexe — catalogue HuggingFace (vérifié 2026-06-02)

Base URL : `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/`
Schéma : `{baseUrl}{fileName}`. Les redirections CDN sont déjà gérées par `HttpModelDownloader`.

**Important :** les variantes quantisées ne sont pas uniformes — `q5_1` pour les petits
modèles, `q5_0` pour les gros, et certaines combos `.en` quantisées n'existent pas. Le
catalogue ci-dessous est la liste **réelle** (31 fichiers), pas une grille théorique.

| Famille | Fichier | Taille |
|---|---|---|
| tiny | ggml-tiny.bin | 77.7 MB |
| tiny | ggml-tiny.en.bin | 77.7 MB |
| tiny | ggml-tiny-q5_1.bin | 32.2 MB |
| tiny | ggml-tiny-q8_0.bin | 43.5 MB |
| tiny | ggml-tiny.en-q5_1.bin | 32.2 MB |
| tiny | ggml-tiny.en-q8_0.bin | 43.6 MB |
| base | ggml-base.bin | 148 MB |
| base | ggml-base.en.bin | 148 MB |
| base | ggml-base-q5_1.bin | 59.7 MB |
| base | ggml-base-q8_0.bin | 81.8 MB |
| small | ggml-small.bin | 488 MB |
| small | ggml-small.en.bin | 488 MB |
| small | ggml-small-q5_1.bin | 190 MB |
| small | ggml-small-q8_0.bin | 264 MB |
| small | ggml-small.en-q5_1.bin | 190 MB |
| small | ggml-small.en-q8_0.bin | 264 MB |
| medium | ggml-medium.bin | 1.53 GB |
| medium | ggml-medium.en.bin | 1.53 GB |
| medium | ggml-medium-q5_0.bin | 539 MB |
| medium | ggml-medium-q8_0.bin | 823 MB |
| medium | ggml-medium.en-q5_0.bin | 539 MB |
| medium | ggml-medium.en-q8_0.bin | 823 MB |
| large | ggml-large-v1.bin | 3.09 GB |
| large | ggml-large-v2.bin | 3.09 GB |
| large | ggml-large-v2-q5_0.bin | 1.08 GB |
| large | ggml-large-v2-q8_0.bin | 1.66 GB |
| large | ggml-large-v3.bin | 3.1 GB |
| large | ggml-large-v3-q5_0.bin | 1.08 GB |
| large | ggml-large-v3-turbo.bin | 1.62 GB |
| large | ggml-large-v3-turbo-q5_0.bin | 574 MB |
| large | ggml-large-v3-turbo-q8_0.bin | 874 MB |

Un avertissement UI signale que `medium`/`large` sont lents en VR sur Quest, mais ils
restent proposés (choix produit explicite « toutes les variantes »).
