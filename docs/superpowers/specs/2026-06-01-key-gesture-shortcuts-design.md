# Design — Raccourcis avancés sur touches physiques (multi-tap / appui long / combo)

**Date :** 2026-06-01
**Statut :** Validé (brainstorming) — prêt pour plan d'implémentation

## Problème

Aujourd'hui les touches physiques (Volume +, Volume −, Back) mappent **une pression = une
action** via `TermuxActivity.handleCustomVolumeKey()` / `handleCustomBackKey()`, qui lisent une
string de comportement dans les `SharedPreferences` et dispatchent immédiatement.

On veut **multiplier virtuellement le nombre de touches** disponibles en VR en ajoutant des
gestes : double tap, triple tap, appui long, et un combo (Volume + & Volume − pressés ensemble).

## Périmètre — matrice des gestes (15 slots)

| Touche | Gestes |
|---|---|
| Volume + | simple · double · triple · long |
| Volume − | simple · double · triple · long |
| Back | simple · double · triple · long |
| Combo (Vol+ & Vol−) | simple · double · triple |

Chaque slot mappe sur le **jeu d'actions existant**, réutilisé tel quel :
`default` / `escape` / `toggle_keyboard` / `voice_input` / `macro` (la macro encode n'importe
quelle séquence via la syntaxe `{tag}` de `MacroExecutor`). Pour le combo, `default` = « ne rien
faire » (aucun volume système possible à deux touches).

## Décisions de conception (verrouillées)

1. **Latence du simple tap (décision A).** Le délai de détection ne s'applique qu'aux touches où
   un geste supplémentaire est réellement *armé*. Une touche qui n'a que le simple tap configuré
   (rien d'autre, et non impliquée dans un combo armé) déclenche **sur le key-down**, identique au
   comportement actuel — zéro régression.
2. **Latence du combo (décision A).** La fenêtre de tolérance combo ne ralentit les simples appuis
   de Vol+/Vol− que **si** le combo a une action configurée ; sinon les volumes restent
   instantanés.
3. **Délais configurables par presets**, pas en millisecondes brutes : un réglage unique
   « Sensibilité des gestes » à 3 niveaux (Réactif / Normal / Tolérant).
4. **Pas de retour haptique** sur l'appui long (hors périmètre).
5. **Zéro migration** : les 3 clés de prefs existantes deviennent le slot « simple » de leur
   touche, on n'y touche pas.
6. **Volume système préservé (option B — émulation).** Comme la détection consomme le key-down
   d'une touche volume (on avale donc le volume système), quand le simple tap résolu vaut `default`
   on **reproduit** le volume système via `AudioManager.adjustStreamVolume(STREAM_MUSIC, raise/lower,
   FLAG_SHOW_UI)`. L'utilisateur garde le volume sur simple tap *et* gagne double/triple/long.
7. **Back est sécurité-critique.** Dès qu'un geste est armé sur Back, on consomme **tous** les
   events Back et on émet `\033` nous-mêmes : un event Back non consommé partirait dans `super` et
   **fermerait l'Activity**.

## Architecture

**Approche retenue : module `KeyGestureDetector` dédié** dans `com.voidterm.input` (à côté de
`QuestInputHandler`, qui intercepte déjà des touches selon le même pattern).

Séparation mécanisme / politique :
- **`KeyGestureDetector`** = mécanisme. Encapsule toute la machine à états temporisée (comptage de
  taps, timers d'appui long, fenêtre de combo). Ne lit **jamais** les `SharedPreferences`.
- **`TermuxActivity`** = politique. Traduit un geste résolu en action via les prefs.

### Interface publique de `KeyGestureDetector`

```
boolean onKeyDown(int keyCode, KeyEvent event)
boolean onKeyUp(int keyCode, KeyEvent event)
void    setArmed(armedSet)            // quels (touche, geste) sont configurés
void    setTiming(GestureTiming t)    // les 3 délais résolus depuis le preset
void    reset()                       // annule tous les timers (appelé en onPause)

interface GestureListener {
    void onGesture(KeyId key, Gesture gesture)
}
```

- `KeyId ∈ { VOL_UP, VOL_DOWN, BACK, COMBO }`
- `Gesture ∈ { SINGLE, DOUBLE, TRIPLE, LONG }` (le combo n'émet jamais `LONG`)

L'« armed set » fourni par `TermuxActivity` est ce qui permet les décisions A : le détecteur sait
quels gestes attendre et donc s'il doit retarder ou non.

### `GestureTiming` (objet de config immuable)

Suit le pattern maison `SharedPreferences → objet immuable` (cf. `WhisperConfig`, `AudioConfig`).
3 champs, construits depuis le preset :

| Preset | `multiTapWindowMs` | `longPressMs` | `comboWindowMs` |
|---|---|---|---|
| ⚡ Réactif | 200 | 400 | 50 |
| ◐ Normal *(défaut)* | 280 | 500 | 60 |
| 🐢 Tolérant | 400 | 700 | 90 |

### Règles de résolution (machine à états par touche)

1. **Seul le simple armé** (rien d'autre, hors combo) → action émise au **key-down** (instantané).
2. **Multi-tap armé** → comptage des taps ; dès qu'on atteint le **max armé** pour la touche, on
   émet immédiatement (pas d'attente du dernier niveau) ; sinon on attend `multiTapWindowMs` puis
   on émet le geste correspondant au compteur (résolu au niveau armé ≤ compteur).
3. **Long armé** → timer `longPressMs` au key-down ; expiration pendant le maintien → `LONG`
   (one-shot), le key-up suivant ne produit rien.
4. **Combo armé** → au key-down d'un volume, son traitement individuel est différé de
   `comboWindowMs` ; si l'autre volume descend dans la fenêtre → mode combo (comptage propre
   simple/double/triple) ; sinon on reprend le traitement individuel normal de la touche.

Détails :
- Le timer d'appui long d'une touche volume ne démarre qu'après expiration de la fenêtre combo
  (sinon un maintien de combo pourrait déclencher un faux appui long individuel).
- Les events de répétition Android (`KeyEvent.getRepeatCount() > 0`) sont ignorés ; le détecteur
  gère son propre timing à partir du premier key-down.
- Le temps vient de `KeyEvent.getEventTime()` + un `Handler` (main looper), ce qui rend la logique
  pilotable en test via le shadow looper de Robolectric.

## Stockage (`SharedPreferences` « voidterm_settings »)

Clés existantes réutilisées comme slot « simple » (aucune migration) :

| Slot | behavior | macro |
|---|---|---|
| Vol+ simple | `volume_up_behavior` | `volume_up_macro` |
| Vol− simple | `volume_down_behavior` | `volume_down_macro` |
| Back simple | `back_key_behavior` | `back_key_macro` |

12 nouvelles clés, schéma uniforme `gesture_<touche>_<geste>` (+ `_macro`) :

- `gesture_volup_double` / `_triple` / `_long` (+ `_macro`)
- `gesture_voldown_double` / `_triple` / `_long` (+ `_macro`)
- `gesture_back_double` / `_triple` / `_long` (+ `_macro`)
- `gesture_combo_single` / `_double` / `_triple` (+ `_macro`)
- `gesture_timing_preset` ∈ { `fast`, `normal`, `slow` } (défaut `normal`)

Toutes ces clés sont ajoutées comme `public static final` dans `SettingsDialog` (règle projet :
aucune string brute ailleurs). Le jeu de valeurs de behavior réutilise les constantes existantes
(`VOLUME_DEFAULT`, `BACK_ESCAPE`, `BACK_TOGGLE_KEYBOARD`, `BACK_MACRO`, `BACK_VOICE`).

## Refactor du dispatch (`TermuxActivity`)

Le `switch` sur le behavior est aujourd'hui dupliqué dans `handleCustomBackKey()` et
`handleCustomVolumeKey()` (même connaissance, 2 occurrences → extraction mandée par la règle DRY).

Extraire :

```
void dispatchKeyAction(KeyId key, String behavior, String macroPrefKey)
   // escape | toggle_keyboard | macro | voice_input
   // default → si key est un volume : AudioManager.adjustStreamVolume(raise/lower) ; sinon no-op
```

`KeyId` est passé pour que la branche `default` sache émuler le bon sens du volume (raise pour
`VOL_UP`, lower pour `VOL_DOWN`). Dans le chemin non-armé hérité (volume non intercepté),
`default` reste un `return false` classique côté `handleCustomVolumeKey`.

Flux complet après refactor :

```
onKeyDown / onKeyUp
   → QuestInputHandler (inchangé, première chance)
   → KeyGestureDetector.onKeyDown/onKeyUp  (résout le geste)
   → GestureListener.onGesture(key, gesture)
   → lookup prefs du slot (key, gesture) → (behavior, macroKey)
   → dispatchKeyAction(behavior, macroKey)
```

Le slot « simple » de Vol+/Vol−/Back continue de lire les clés existantes.

## Contrat de consommation `onKeyDown` / `onKeyUp` (zone critique, non testée auto)

`onKeyDown` est **synchrone** : il doit retourner `true` (consommé) ou `false` (laissé à
`super`/`TerminalView`) immédiatement, alors que la détection de geste doit attendre. La règle de
retour est donc dictée par l'état « armé » de la touche, fourni par `setArmed(...)`.

**La décision « consommer ou non » vit dans `KeyGestureDetector`, pas dans `TermuxActivity`** :
`detector.onKeyDown(...)` renvoie `true` ssi la touche est armée, et `TermuxActivity.onKeyDown`
retourne simplement cette valeur. Ainsi la logique critique de consommation est dans le module
**testé** (Robolectric), pas dans la zone Activity non couverte ; `TermuxActivity` ne fait que
relayer le booléen et exécuter l'action émise (policy).

| Situation de la touche | key-down retourne | Action |
|---|---|---|
| **Aucun geste armé** (sauf le simple) | `false` si simple = `default` (volume) ; sinon traité au key-down et `true` | comportement actuel, aucune interception |
| **Au moins un geste armé** (double/triple/long, ou combo pour un volume) | **toujours `true`** | l'event est mis en file dans le détecteur ; rien ne part vers `super` |

Conséquences imposées (pas des choix) :

- **Back armé → tout consommer.** On n'émet jamais `false` pour un Back armé, sinon l'event ferme
  l'Activity. Le simple tap `escape` est produit par nous via `current.write("\033")` (et non par
  délégation à `TerminalView`, qui ne verrait jamais l'event). Quand **aucun** geste n'est armé sur
  Back, on conserve le chemin actuel (délégation `shouldBackButtonBeMappedToEscape` possible).
- **Volume armé → tout consommer → émuler le volume.** Le key-down volume est avalé ; si le geste
  résolu est le simple tap mappé `default`, `dispatchKeyAction` appelle
  `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE/LOWER, FLAG_SHOW_UI)` (décision 6).
  La distinction Vol+ vs Vol− pour raise/lower est connue du slot émetteur.
- **`reset()` en `onPause`** : si des events sont en file (touche enfoncée au moment du pause), les
  timers sont annulés sans émettre de geste — évite un état bloqué.

## UI (`SettingsActivity`, layout programmatique, accordéon)

Nouvelle section **« Gestes de touches »** :

```
🎚  Sensibilité des gestes : [ Réactif | Normal | Tolérant ]    ← 1 spinner global

▸ Volume +
     Simple   : [Spinner] (+ macro si "Macro")     ← lié à volume_up_behavior (existant)
     Avancé ▾
        Double : [Spinner] (+ macro)
        Triple : [Spinner] (+ macro)
        Long   : [Spinner] (+ macro)
▸ Volume −    (idem)
▸ Back        (idem)
▸ Combo (Vol+ & Vol−)
     Simple / Double / Triple : [Spinner] (+ macro)
```

- Le **simple tap reste visible** en haut de chaque bloc ; double/triple/long repliés derrière un
  **« Avancé ▾ »** par touche (réutilise le pattern « Advanced... » de la section Transcription) —
  on ne noie pas l'utilisateur sous 15 spinners sur un écran VR.
- Une **fabrique de ligne** `addGestureRow(label, behaviorKey, macroKey)` génère chaque ligne
  spinner+macro (le champ macro n'apparaît que quand « Macro » est sélectionné, comme l'existant)
  → pas de duplication 15×.

## Cycle de vie (`TermuxActivity`)

- Détecteur créé en `onCreate`, `GestureListener` câblé. `onKeyDown`/`onKeyUp` lui délèguent après
  `QuestInputHandler`.
- `onResume` : recharge l'« armed set » + le `GestureTiming` depuis les prefs (l'utilisateur
  revient de `SettingsActivity` ; `onResume` y fait déjà le sync de thème).
- `onPause` : `detector.reset()` (annule les timers en attente, évite un état bloqué et une fuite),
  à côté du « cancel voice » existant.

## Tests

Tests unitaires Robolectric sur `KeyGestureDetector` (shadow looper pour avancer l'horloge) :

- simple instantané quand seul le simple est armé
- double tap dans la fenêtre → `DOUBLE`
- double résolu au timeout (compteur < max armé)
- triple tap → `TRIPLE`
- appui long au seuil → `LONG`
- appui long qui annule le tap (pas de `SINGLE` derrière)
- combo : deux touches dans la fenêtre → `COMBO/SINGLE`
- combo multi-tap → `COMBO/DOUBLE`, `COMBO/TRIPLE`
- fenêtre combo expirée (une seule touche) → retombe sur la touche seule
- `reset()` annule tous les timers en attente
- **contrat de consommation** : `onKeyDown` renvoie `true` ssi la touche est armée, `false` sinon
  (couvre la sécurité Back et le passthrough volume non-armé sans toucher à l'Activity)

Le dispatch (`TermuxActivity`) reste en **test manuel sur Quest** (convention projet :
l'intégration se teste à la main).

## Hors périmètre

- Retour haptique sur l'appui long.
- Gestes sur d'autres touches que Vol+ / Vol− / Back.
- Appui long sur le combo.
- Délais réglables en millisecondes brutes (remplacés par les 3 presets).
