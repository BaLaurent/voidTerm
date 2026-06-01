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
boolean dispatchKeyAction(String behavior, String macroPrefKey)
   // escape | toggle_keyboard | macro | voice_input ; default → false (non consommé)
```

Flux complet après refactor :

```
onKeyDown / onKeyUp
   → QuestInputHandler (inchangé, première chance)
   → KeyGestureDetector.onKeyDown/onKeyUp  (résout le geste)
   → GestureListener.onGesture(key, gesture)
   → lookup prefs du slot (key, gesture) → (behavior, macroKey)
   → dispatchKeyAction(behavior, macroKey)
```

Le slot « simple » de Vol+/Vol−/Back continue de lire les clés existantes ; le cas `BACK_ESCAPE`
en simple tap peut soit déléguer à `TerminalView` (comportement actuel via
`shouldBackButtonBeMappedToEscape`) lorsque aucun autre geste n'est armé sur Back, soit envoyer
`\033` directement lorsque Back est intercepté pour la détection de gestes. Le plan
d'implémentation tranchera le détail en lisant le chemin de dispatch existant ; le comportement
observable du simple tap reste identique.

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

Le dispatch (`TermuxActivity`) reste en **test manuel sur Quest** (convention projet :
l'intégration se teste à la main).

## Hors périmètre

- Retour haptique sur l'appui long.
- Gestes sur d'autres touches que Vol+ / Vol− / Back.
- Appui long sur le combo.
- Délais réglables en millisecondes brutes (remplacés par les 3 presets).
