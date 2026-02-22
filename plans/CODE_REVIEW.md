# VoidTerm — Revue de Code Exhaustive

**Date :** 2026-02-22
**Methode :** 5 agents specialises en parallele (READ-ONLY)
**Scope :** ~7 300 lignes custom (27 Java + 1 C++/JNI)

---

## Vue d'ensemble

| Domaine | Agent | Findings | CRITICAL | HIGH | MEDIUM | LOW |
|---|---|---|---|---|---|---|
| Concurrence | `concurrency-reviewer` | 11 | 1 | 2 | 3 | 5 |
| JNI/Native | `jni-reviewer` | 11 | 0 | 2 | 5 | 4 |
| UI | `ui-reviewer` | 13 | 0 | 4 | 3 | 6 |
| Architecture | `architecture-reviewer` | 14 | 0 | 3 | 6 | 5 |
| Config | `config-reviewer` | 12 | 0 | 5 | 4 | 3 |
| **TOTAL** | | **61** | **1** | **16** | **21** | **23** |

---

## Findings croises (confirmes par 2+ agents independants)

| Bug | Rapporte par | Confiance |
|---|---|---|
| `onScale()` hardcode a 20 | UI + Architecture + Config | 3x |
| `beamSpinner` initial-fire casse auto-tune | Config + UI | 2x |
| TOCTOU `loadModel()` / `transcribeThread` race | Concurrence + JNI | 2x |

---

## CRITICAL (1)

### C1 — `benchmarkTranscribe()` ne set pas `transcribeThread` → use-after-free natif (SIGSEGV)

- **Fichier :** `WhisperBridge.java`
- **Domaine :** Concurrence
- **Threads :** WhisperBridge-Transcribe (benchmark), main thread (release)
- **Description :** `benchmarkTranscribe()` lance un thread de benchmark mais n'assigne jamais `transcribeThread`. Si `release()` est appele pendant le benchmark (ex: changement de modele), il ne peut pas `join()` le thread et appelle `nativeFree()` immediatement → use-after-free natif.
- **Fenetre :** 600-1200ms (duree du benchmark)
- **Reproduction :** Utilisateur change de modele dans Settings pendant l'auto-tuning initial.
- **Recommendation :** Assigner `transcribeThread = thread` avant `thread.start()` dans `benchmarkTranscribe()`.
- **Effort :** 5 min

---

## HIGH (16)

### Concurrence

#### H1 — Race TOCTOU `loadModel()` : check + free non atomiques

- **Fichier :** `WhisperBridge.java:161, 214-216`
- **Threads :** VoiceInput-Pipeline, main thread
- **Description :** `isTranscribing.get()` n'est pas atomique avec `nativeFree(contextHandle)`. Un `transcribe()` peut demarrer entre le check et la liberation de l'ancien contexte → use-after-free / SIGSEGV.
- **Recommendation :** Prendre `contextLock` autour du check + free + init.
- **Effort :** 30 min

#### H2 — `transcribeThread` null entre CAS et assignation

- **Fichier :** `WhisperBridge.java`
- **Threads :** VoiceInput-Pipeline, main thread
- **Description :** `release()` lit `transcribeThread` qui peut etre `null` entre le CAS `isTranscribing` et l'assignation `transcribeThread = thread` → skip du join → `nativeFree` pendant transcription active.
- **Recommendation :** Assigner `transcribeThread` avant `thread.start()`, ou synchroniser l'acces.
- **Effort :** 10 min

#### H3 — `whisperBridge` non-volatile dans VoiceInputManager

- **Fichier :** `VoiceInputManager.java`
- **Threads :** main thread (init), VoiceInput-Pipeline (read)
- **Description :** Le champ `whisperBridge` est lu depuis le thread pipeline sans garantie de visibilite JMM. Le thread pourrait voir `null` ou un etat partiellement construit.
- **Recommendation :** Declarer `volatile WhisperBridge whisperBridge`.
- **Effort :** 2 min

### JNI

#### H4 — Pas d'ExceptionCheck apres CallVoidMethod dans streaming callback

- **Fichier :** `whisper_jni.cpp:69`
- **Description :** Si Java leve une exception durant le callback (ex: OOM dans `mainHandler.post()`), les appels JNI suivants (dont `NewStringUTF` ligne 271) s'executent avec une exception pendante → comportement indefini / crash.
- **Recommendation :** Ajouter `if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); return; }` apres chaque `CallVoidMethod`.
- **Effort :** 10 min

#### H5 — Race TOCTOU loadModel (confirme cote JNI)

- **Fichier :** `WhisperBridge.java:161, 214-216`
- **Description :** Meme finding que H1, confirme par le jni-reviewer via l'analyse du code natif. Le `nativeTranscribe()` accede au context handle qui peut etre libere par `loadModel()`.
- **Note :** Dedup avec H1 — fix commun.

### UI

#### H6 — Arrow repeat handler sans cleanup dans GameBoyControlPanel

- **Fichier :** `GameBoyControlPanel.java:528-553`
- **Description :** `setupArrowRepeat()` utilise un `Runnable` anonyme poste via `v.postDelayed()`. Pas de `onDetachedFromWindow()`. Si la vue est detachee pendant un appui maintenu, `v.getHandler()` retourne `null` → NPE. `CompactToolbar` a le fix correct (`cancelRepeat()` + `onDetachedFromWindow()`) qui n'a jamais ete backporte.
- **Impact :** Crash potentiel si Activity detruite pendant maintien d'une fleche.
- **Recommendation :** Backporter le pattern de `CompactToolbar` : `activeRepeatRunnable`, `activeRepeatView`, `cancelRepeat()`, `onDetachedFromWindow()`.
- **Effort :** 15 min

#### H7 — Pinch-to-zoom `currentSize` hardcode a 20

- **Fichier :** `VoidTermTerminalViewClient.java:52`
- **Description :** `int currentSize = 20;` ignore la taille de police reelle. Chaque geste de zoom "reset" a base 20 au lieu d'utiliser la taille courante (ex: 32pt). Confirme par 3 agents (UI, Architecture, Config).
- **Impact :** Pinch-to-zoom completement casse pour tout utilisateur ayant change la taille de police.
- **Recommendation :** Lire `SharedPreferences("voidterm_style").getInt("font_size", 20)` ou `terminalView.getTextSize()`.
- **Effort :** 10 min

#### H8 — Streaming mode bypass overlay — texte direct au PTY sans review

- **Fichier :** `VoiceInputManager.java:208-246`
- **Description :** CLAUDE.md documente `TranscriptionOverlay.updateStreamingText()` qui n'existe pas. En realite, le texte part directement au PTY via `callback.onVoiceTextReady(delta)`, sans possibilite de review ou cancel. L'overlay affiche un spinner pendant la transcription, puis transite directement a IDLE (pas SHOWING_RESULT).
- **Impact :** Streaming mode supprime l'etape de review/cancel. Le texte est commis au terminal sans validation utilisateur.
- **Recommendation :** Soit (a) implementer `updateStreamingText()` avec review dans l'overlay, soit (b) ajouter un avertissement dans Settings que le streaming envoie directement au terminal.
- **Effort :** 1-2h

#### H9 — `isHapticEnabled()` lit SharedPreferences a chaque press (14+ sites)

- **Fichier :** `GameBoyControlPanel.java` (13 sites) + `CompactToolbar.java` (7 sites)
- **Description :** `SettingsDialog.isHapticEnabled(context)` ouvre SharedPreferences et acquiert un read lock a chaque appel. Appele a 10Hz pendant le repeat des fleches. `VoidTermTerminalViewClient` suit deja le bon pattern (volatile + listener).
- **Impact :** Lock contention inutile sur le UI thread sous charge.
- **Recommendation :** Ajouter `volatile boolean hapticEnabled` dans les deux panels, invalide via `OnSharedPreferenceChangeListener`.
- **Effort :** 20 min

### Architecture

#### H10 — 2 unsafe downcasts Activity → TermuxActivity

- **Fichier :** `SettingsDialog.java:357, 385` + `TermuxTerminalSessionClient.java`
- **Description :** Cast `(TermuxActivity) activity` dans des lambdas. ClassCastException si instancie depuis un test harness ou un autre contexte.
- **Recommendation :** Changer le constructeur pour accepter `TermuxActivity` directement, ou `instanceof` guard.
- **Effort :** 10 min

#### H11 — BootstrapCallback sans guard `isDestroyed()`

- **Fichier :** `TermuxBootstrapInstaller.java`
- **Description :** Le callback bootstrap peut s'executer apres la destruction de l'Activity.
- **Recommendation :** Ajouter `if (activity.isDestroyed()) return;` en debut de callback.
- **Effort :** 5 min

#### H12 — `viewClient.release()` jamais appele depuis `onDestroy()`

- **Fichier :** `TermuxActivity.java`
- **Description :** `VoidTermTerminalViewClient.release()` existe mais n'est jamais appele. Le `SharedPreferences` listener n'est jamais desinscrit. `SharedPreferences` utilise `WeakHashMap` donc pas de fuite permanente, mais c'est du code mort.
- **Recommendation :** Appeler `viewClient.release()` dans `onDestroy()`.
- **Effort :** 2 min

### Config

#### H13 — `beamSpinner` listener fire sur dialog open → auto-tune casse

- **Fichier :** `SettingsDialog.java:258-266`
- **Description :** Android poste un `SelectionNotifier` quand `setSelection()` est appele. `onItemSelected()` fire une fois avec la selection programmatique. Le `beamSpinner` listener appelle `DeviceProfiler.markUserOverride(prefs, KEY_WHISPER_BEAM_SIZE)` — marque `beam_size` comme override utilisateur a chaque ouverture de dialog.
- **Impact :** Apres la premiere ouverture des Settings, DeviceProfiler ne met plus jamais a jour `whisper_beam_size`. Les utilisateurs sur tier MEDIUM devraient avoir `beam_size=3` mais restent a `5`.
- **Recommendation :** Ajouter un flag `boolean initializing = true` avant `setSelection()`, mis a `false` apres `dialog.show()`. Garder `markUserOverride` derriere `if (!initializing)`.
- **Effort :** 15 min

#### H14 — `KEY_USE_GPU` duplique dans 2 classes

- **Fichier :** `SettingsDialog.java:34` + `VoiceInputManager.java:46`
- **Description :** Deux constantes independantes avec la meme valeur `"use_gpu"`. Un renommage dans SettingsDialog ne serait pas detecte par le compilateur dans VoiceInputManager.
- **Recommendation :** Supprimer `VoiceInputManager.KEY_USE_GPU`, reference `SettingsDialog.KEY_USE_GPU` (rendu `public`).
- **Effort :** 5 min

#### H15 — Raw string literals pour cles whisper dans VoiceInputManager

- **Fichier :** `VoiceInputManager.java:344-354`
- **Description :** `readWhisperConfig()` hardcode 10 cles en raw strings au lieu de referencer `SettingsDialog.KEY_*`. Duplique aussi `PREFS_NAME`. Un renommage de cle = settings silencieusement ignores.
- **Recommendation :** Rendre `SettingsDialog.KEY_*` public, remplacer tous les raw strings.
- **Effort :** 20 min

#### H16 — DeviceProfiler utilise raw strings pour toutes les cles whisper

- **Fichier :** `DeviceProfiler.java:35-40, 133-144, 161-174`
- **Description :** Meme probleme que H15 mais dans DeviceProfiler. `AUTOTUNE_KEYS` et `applyDefaults()` utilisent des raw strings car les constantes SettingsDialog sont package-private.
- **Recommendation :** Meme fix que H15 — rendre les constantes public.
- **Effort :** 15 min

#### H17 — `interface_theme` key isolee dans InterfaceTheme, invisible a SettingsDialog

- **Fichier :** `InterfaceTheme.java:54`
- **Description :** `KEY_THEME = "interface_theme"` est private dans InterfaceTheme. Ecrit dans `voidterm_settings` mais absent du registre de SettingsDialog. Un "Reset all settings" raterait cette cle.
- **Recommendation :** Deplacer `KEY_THEME` vers SettingsDialog comme constante publique.
- **Effort :** 5 min

---

## MEDIUM (21)

### Concurrence (3)

| # | Finding | Fichier |
|---|---|---|
| M1 | State → RECORDING avant `isModelLoaded()` check (hors lock) — fenetre ou PTT release verrait RECORDING sans audio | `VoiceInputManager.java` |
| M2 | `reloadModel()` sans guard etat courant — peut detruire bridge pendant transcription | `VoiceInputManager.java` |
| M3 | TOCTOU `loadModel()` : `isTranscribing.get()` puis `isLoading.CAS` separes | `WhisperBridge.java` |

### JNI (5)

| # | Finding | Fichier |
|---|---|---|
| M4 | GetObjectClass local ref non supprimee, tenue pendant `whisper_full()` (plusieurs secondes) | `whisper_jni.cpp` |
| M5 | GetMethodID exception non effacee si methode non trouvee | `whisper_jni.cpp` |
| M6 | `params.language`/`initial_prompt` = raw `char*` JNI, lifetime fragile par convention d'ordre | `whisper_jni.cpp` |
| M7 | Pas d'ExceptionCheck apres GetStringUTFChars / GetFloatArrayRegion | `whisper_jni.cpp` |
| M8 | Pas de `-Wall` dans CMakeLists.txt | `CMakeLists.txt` |

### UI (3)

| # | Finding | Fichier |
|---|---|---|
| M9 | Macro long-press capture index avec `currentPage` live (pas capture au moment du press) | `GameBoyControlPanel.java:286-313` |
| M10 | SettingsDialog force cast `activity` → `TermuxActivity` dans lambdas | `SettingsDialog.java:357, 385` |
| M11 | `findFontButton`/`findColorButton` O(N^2) traversal a chaque clic | `TerminalStyleDialog.java:165-178` |

### Architecture (6)

| # | Finding | Fichier |
|---|---|---|
| M12 | `onScale()` hardcode font 20 (cross-finding) | `VoidTermTerminalViewClient.java:52` |
| M13 | `applyTheme()` perd etat Ctrl/Shift/MacroPage | `TermuxActivity.java` |
| M14 | `updatePanelVisibility()` lit SharedPrefs a chaque layout pass | `TermuxActivity.java` |
| M15 | `handleCustomBackKey()` duplique preference cachee | `TermuxActivity.java` |
| M16 | Pas de `onSaveInstanceState()` (acceptable sur Quest landscape-only) | `TermuxActivity.java` |
| M17 | `viewClient.release()` existe mais jamais appele (code mort) | `TermuxActivity.java` |

### Config (4)

| # | Finding | Fichier |
|---|---|---|
| M18 | MacroStore silently discards data pour array lengths != 4 et != 12 | `MacroStore.java:47-70` |
| M19 | `KEY_COLOR_INDEX` defaults inconsistants (0 vs -1) entre `show()` et `applySavedStyle()` | `TerminalStyleDialog.java:118, 296` |
| M20 | VoiceInputManager duplique `PREFS_NAME` independamment de SettingsDialog | `VoiceInputManager.java:44` |
| M21 | `back_key_macro` texte perdu si dialog dismiss via system back key | `SettingsDialog.java:459-465` |

---

## LOW (23)

### Concurrence (5)

| # | Finding | Fichier |
|---|---|---|
| L1 | JNI `nativeIsLoaded()` appele sous `contextLock` | `WhisperBridge.java` |
| L2 | `onNativeSegment` peut poster apres destroy (inoffensif grace au guard VIM) | `WhisperBridge.java` |
| L3 | Double-init `cachedConfig` benigne (objet immutable) | `VoiceInputManager.java` |
| L4 | Race theorique apres timeout join(2000) dans AudioCapture.release() | `AudioCapture.java` |
| L5 | `pttKeyCode` non-volatile dans QuestInputHandler | `QuestInputHandler.java` |

### JNI (4)

| # | Finding | Fichier |
|---|---|---|
| L6 | `ggml-cpu-hbm.cpp` inutile sur Quest | `CMakeLists.txt` |
| L7 | `volatile int` vs `std::atomic` pour `g_abort_flag` (UB C++ formel, sur en pratique ARM64) | `whisper_jni.cpp` |
| L8 | `transcribeThread` assigne avant `thread.start()` — race theorique avec `release()` | `WhisperBridge.java` |
| L9 | Doc CLAUDE.md `SDK_INT >= 27` vs code cpuinfo fphp — divergence | Documentation |

### UI (6)

| # | Finding | Fichier |
|---|---|---|
| L10 | `descriptionForLabel()` manque S-TAB et S-Enter dans CompactToolbar vs GameBoy | `CompactToolbar.java:338-353` |
| L11 | Swipe gesture ne reset pas `tracking` sur window detach | `CompactToolbar.java:256-298` |
| L12 | `applyColorScheme()` ecrit directement dans `mCurrentColors[]` sans sync render thread | `TerminalStyleDialog.java:255-263` |
| L13 | MacroEditDialog rejection silencieuse quand label/cmd vide — pas de feedback utilisateur | `MacroEditDialog.java:66-68` |
| L14 | DragTouchListener inner class non-static (leak mitige par cleanup) | `LayoutEditMode.java:146-170` |
| L15 | `InterfaceTheme.current()` lit SharedPreferences a chaque appel (rarement en hot path) | `InterfaceTheme.java:77-86` |

### Architecture (5)

| # | Finding | Fichier |
|---|---|---|
| L16 | Bootstrap lit fichiers complets en memoire | `TermuxBootstrapInstaller.java` |
| L17 | CompactToolbar couple son listener interface sur GameBoyControlPanel | `CompactToolbar.java` |
| L18 | Init ordering fragile : `QuestInputHandler(null)` possible | `TermuxActivity.java` |
| L19 | God Object : 15 responsabilites, 12 classes instanciees | `TermuxActivity.java` |
| L20 | Dependencies circulaires Activity <-> ViewClient/SessionClient/SettingsDialog | Multiple |

### Config (3)

| # | Finding | Fichier |
|---|---|---|
| L21 | MacroExecutor : chaine `{wait:N}` illimitee — epuisement ressources local | `MacroExecutor.java:46-57` |
| L22 | `LayoutStore.save()` avale silencieusement toutes les exceptions | `LayoutStore.java:55` |
| L23 | `DeviceProfiler.applyDefaults()` set toujours `proportional_context = true` quel que soit le tier | `DeviceProfiler.java:139-141` |

---

## God Object — TermuxActivity

**15 responsabilites identifiees, 12 classes instanciees directement.**

### Dependencies circulaires confirmees

```
TermuxActivity ←→ VoidTermTerminalViewClient  (champs directs + getters bidirectionnels)
TermuxActivity ←→ TermuxTerminalSessionClient (instanceof downcast dans 3 callbacks)
TermuxActivity ←→ SettingsDialog              (unsafe downcast dans theme spinner + fullscreen toggle)
```

### Boundaries d'extraction proposees

| Classe extraite | Responsabilite | Fichiers sources |
|---|---|---|
| `ModelFileHandler` | Selection/copie de modele, file picker | SettingsDialog (model section) |
| `BootstrapOrchestrator` | Installation bootstrap, progress | TermuxBootstrapInstaller callbacks |
| `PanelController` | Visibilite/sync GameBoy + Toolbar | updatePanelVisibility, modifier sync |
| `TerminalSessionManager` | Sessions, TerminalView setup | Session creation, view binding |
| `VoicePipelineCoordinator` | Wire VoiceInputManager ↔ Activity | VoiceInputCallback, overlay mgmt |

---

## GameBoyControlPanel / CompactToolbar — Duplication

**~155 lignes dupliquees en 9 categories :**

| Categorie | GBCP lignes | CT lignes | Status |
|---|---|---|---|
| `descriptionForLabel()` | 682-699 (18) | 338-353 (16) | Near-identical (CT manque 2 cases) |
| `makeButtonDrawable()` | 701-718 (17) | 355-374 (20) | Near-identical (stroke diff) |
| `setupArrowRepeat()` | 528-553 (25) | 376-405 (30) | CT superieur (cleanup correct) |
| `updateMacroPage()` | 450-455 (6) | 247-252 (6) | Identical |
| `set/getCurrentMacroPage()` | 439-448 (10) | 236-245 (10) | Identical |
| Modifier state API (6 methods) | 631-657 (27) | 427-453 (27) | Structurally identical |
| `dp()` utility | 737-740 (4) | 457-460 (4) | Identical |
| Macro click/long-click | 291-309 (22) | 186-209 (22) | Near-identical (naming) |
| Macro fields | ~8 | ~8 | Identical declarations |

**Extraction justifiee ?** Non selon CLAUDE.md (regle des 3 uses, seulement 2 consumers). Exception : `dp()` est duplique 4+ fois dans le codebase → extraction justifiee.

---

## SharedPreferences — Catalogue complet

### Fichier `voidterm_settings` (24 cles, 5 classes)

| Cle | Constante ? | Classe | Type | Default |
|---|---|---|---|---|
| `whisper_model_name` | Oui | SettingsDialog | String | `"ggml-base.bin"` |
| `compact_toolbar_enabled` | Oui | SettingsDialog | boolean | `true` |
| `tap_toggle_keyboard` | Oui | SettingsDialog | boolean | `true` |
| `haptic_feedback` | Oui | SettingsDialog | boolean | `true` |
| `fullscreen_mode` | Oui | SettingsDialog | boolean | `false` |
| `use_gpu` | **Duplique** | SettingsDialog + VoiceInputManager | boolean | `false` |
| `back_key_behavior` | Oui | SettingsDialog | String | `"escape"` |
| `back_key_macro` | Oui | SettingsDialog | String | `""` |
| `whisper_language` | Oui | SettingsDialog | String | `"en"` |
| `whisper_translate` | Oui | SettingsDialog | boolean | `false` |
| `whisper_initial_prompt` | Oui | SettingsDialog | String | `""` |
| `whisper_temperature` | Oui | SettingsDialog | float | `0.0f` |
| `whisper_beam_search` | Oui | SettingsDialog | boolean | `false` |
| `whisper_beam_size` | Oui | SettingsDialog | int | `5` |
| `whisper_thread_override` | Oui | SettingsDialog | int | `0` |
| `whisper_suppress_non_speech` | Oui | SettingsDialog | boolean | `false` |
| `whisper_proportional_context` | Oui | SettingsDialog | boolean | `false` |
| `whisper_streaming` | Oui | SettingsDialog | boolean | `false` |
| `interface_theme` | **Isole** | InterfaceTheme seulement | String | `"GAMEBOY"` |
| `autotune_model` | Oui | DeviceProfiler | String | `null` |
| `autotune_benchmark_ms` | Oui | DeviceProfiler | long | `0` |
| `autotune_tier` | Oui | DeviceProfiler | String | `null` |
| `user_overrides` | Oui | DeviceProfiler | StringSet | `{}` |
| `autotune_migrated` | Package-private | DeviceProfiler | boolean | `false` |

### Fichier `voidterm_style` (3 cles)

| Cle | Type | Default (`show()`) | Default (`applySavedStyle()`) |
|---|---|---|---|
| `font_size` | int | `20` | `20` |
| `font_index` | int | `0` | `0` |
| `color_index` | int | `0` | **`-1`** (inconsistant) |

### Fichier `voidterm_macros` (1 cle)

| Cle | Type | Default |
|---|---|---|
| `macros` | String (JSON array) | DEFAULT_MACROS |

### Fichier `voidterm_layout` (1 cle)

| Cle | Type | Default |
|---|---|---|
| `positions` | String (JSON object) | `null` |

### Audit commit/apply

Tous les writes utilisent `apply()` (async). Aucun `commit()` bloquant sur le UI thread.

---

## Plan d'action

### Phase 1 — Fixes critiques (< 2h, 0 risque regression) ✅ COMPLETED

| Prio | Action | Fichier | Status |
|---|---|---|---|
| P0 | ~~Fix `benchmarkTranscribe()` : assigner `transcribeThread` avant `start()`~~ | `WhisperBridge.java` | ✅ |
| P0 | ~~Fix TOCTOU `loadModel()` : lock autour du check + free + init~~ | `WhisperBridge.java` | ✅ |
| P0 | ~~Ajouter `ExceptionCheck` apres `CallVoidMethod` dans streaming callback~~ | `whisper_jni.cpp` | ✅ |
| P0 | ~~Ajouter guard `initializing` pour spinner listeners dans SettingsDialog~~ | `SettingsDialog.java` | ✅ |
| P1 | ~~Rendre `whisperBridge` volatile dans VoiceInputManager~~ | `VoiceInputManager.java` | ✅ |
| P1 | ~~Fix `onScale()` : lire la vraie taille de police~~ | `VoidTermTerminalViewClient.java` | ✅ |

### Phase 2 — Consolidation config (1 sprint) ✅ COMPLETED

| Action | Fichiers | Status |
|---|---|---|
| ~~Rendre `SettingsDialog.KEY_*` public~~ | `SettingsDialog.java` | ✅ |
| ~~Remplacer raw strings dans VoiceInputManager + DeviceProfiler~~ | `VoiceInputManager.java`, `DeviceProfiler.java` | ✅ |
| ~~Supprimer `KEY_USE_GPU` duplique dans VoiceInputManager~~ | `VoiceInputManager.java` | ✅ |
| ~~Deplacer `KEY_THEME` vers SettingsDialog~~ | `InterfaceTheme.java`, `SettingsDialog.java` | ✅ |
| ~~Fix `back_key_macro` perte de donnees sur dismiss~~ | `SettingsDialog.java` | ✅ |
| ~~Supprimer `PREFS_NAME` duplique dans VoiceInputManager~~ | `VoiceInputManager.java` | ✅ |

### Phase 3 — UI robustness (1 sprint) ✅ COMPLETED

| Action | Fichiers | Status |
|---|---|---|
| ~~Backporter `cancelRepeat()`/`onDetachedFromWindow()` de CT vers GBCP~~ | `GameBoyControlPanel.java` | ✅ |
| ~~Decider comportement streaming (overlay review OU warning)~~ | `SettingsDialog.java` | ✅ |
| ~~Cacher `isHapticEnabled` dans les panels (volatile + listener)~~ | `GameBoyControlPanel.java`, `CompactToolbar.java` | ✅ |
| ~~Appeler `viewClient.release()` dans `onDestroy()`~~ | `TermuxActivity.java` | ✅ |
| ~~Guard `isDestroyed()` dans BootstrapCallback~~ | `TermuxActivity.java` | ✅ |
| ~~Fix MacroStore : log + graceful handling pour lengths inattendues~~ | `MacroStore.java` | ✅ |

### Phase 4 — Refactoring structurel (quand pret)

| Action | Fichiers |
|---|---|
| Decomposer God Object TermuxActivity (5-6 classes) | `TermuxActivity.java` + nouveaux fichiers |
| Briser dependencies circulaires avec interfaces/callbacks | Multiple |
| Extraire code duplique GameBoy/Toolbar si 3eme consumer | `GameBoyControlPanel.java`, `CompactToolbar.java` |
| Ajouter `-Wall` au CMakeLists.txt | `CMakeLists.txt` |
| Remplacer `volatile int` par `std::atomic<int>` pour `g_abort_flag` | `whisper_jni.cpp` |
