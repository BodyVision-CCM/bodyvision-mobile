# Sprint S5 — tenir le débit (SCRUM-41)

Ce fichier grandit ticket par ticket. Il ne remplace ni `docs/plan-s3.md` ni `docs/plan-s4.md`,
qui restent les plans des sprints précédents, tels qu'ils ont été exécutés.

---

## SCRUM-41 — Optimiser le multithreading pour tenir les 30 FPS

Branche `feat/scrum-41-debit-30-fps`, depuis `main` à `8f21f9c`.

### Contexte

Le débit du flux live **n'a jamais été mesuré dans l'application**. Le README le dit lui-même, et
les 29,4 images/s de [`CLAUDE.md`](../CLAUDE.md) viennent du spike SCRUM-22, pas d'ici. Le seul
chiffre relevé dans l'application est **23,5 images/s**, pendant un enregistrement, à la
[séance du 17/11/2026](plan-s4.md#vérification-sur-appareil--séance-du-17112026).

Ce ticket ne commence donc pas par une optimisation, il commence par un instrument. Trois raisons
tiennent cet ordre :

1. **On ne sait pas où les images se perdent.** Avec `STRATEGY_KEEP_ONLY_LATEST`, les images que
   CameraX jette avant de nous les livrer sont invisibles — le détail du mécanisme est plus bas,
   lu dans les sources. Le compteur d'écran mesure la sortie de la chaîne et ne dit rien de son
   entrée.
2. **Les 23,5 ne sont pas ce qu'on croyait.** Ce sont les poses **reçues par l'enregistrement**,
   donc une **borne basse** du débit d'inférence : le flux de poses jette les plus anciennes quand
   un collecteur prend du retard (`BufferOverflow.DROP_OLDEST`). Le vrai débit d'inférence pendant
   un enregistrement est donc ≥ 23,5, et personne ne sait de combien.
3. **« Environ 15 images/s sans personne » n'est relevé nulle part.** L'affirmation vient d'un KDoc
   de `telemetry/domain/SeriesRecorder.kt` — « le débit réel va de 15 à 30 images par seconde selon
   la lumière et la chauffe » — repris dans `docs/plan-s4.md`. C'est une hypothèse, et elle a deux
   causes possibles qui ne se corrigent pas du tout de la même façon.

**Ce que « multithreading » veut dire ici, et ce qu'il ne veut pas dire.** Pas d'inférences en
parallèle, pas de second détecteur, pas de pool de threads pour ML Kit : un détecteur traite une
image à la fois, `STREAM_MODE` a besoin des images dans l'ordre, et deux inférences se disputeraient
le même GPU. La contre-pression reste `STRATEGY_KEEP_ONLY_LATEST` plus le portillon
`isInferenceInFlight` — décision figée, **à mesurer, pas à changer**. Côté threads, le travail est
de vérifier que rien d'autre que l'inférence et sa conversion ne tourne sur `bv-pose`, et que le
thread principal ne fait attendre personne.

### Ce que la relecture a changé

Le sous-agent `revue-bodyvision` a rendu la première version **non fusionnable**, avec raison, et
pour un défaut qui tenait à l'objet même du ticket : **un échec d'inférence n'était compté nulle
part.**

`addOnFailureListener` ne faisait qu'un `Log.w` sous l'étiquette `MlKitPoseSource`. Une image dont
ML Kit refuse l'inférence était donc comptée dans `recv` et dans rien d'autre : ni portillon, ni
abandon, ni inférence. On aurait lu `recv=147 drop_gate=0 drop_other=0 inf=98` — quarante-neuf
images disparues sans colonne pour le dire — et le seul indice existant vivait sous une étiquette
que le protocole **filtre**. Pire : c'est l'une des explications candidates des « 15 images par
seconde sans personne » que ce ticket doit départager, et aucune ligne du tableau d'hypothèses ne
correspondait à « `inf` bas, latence normale, capteur normal ». Le coût de la découvrir après la
séance, c'était cinq runs de dix minutes à refaire. Il y a donc maintenant une colonne `fail`, et
les quatre issues d'une image reçue couvrent tous les chemins du code.

Trois autres constats corrigés :

- **Le KDoc de `analyze` était devenu faux.** Il affirmait que la fenêtre après la prise du
  portillon est « la seule fenêtre du fichier où une levée laisserait le portillon fermé pour
  toujours et l'image jamais rendue ». L'instrumentation en a ouvert une seconde, en tête du corps,
  avant le premier `close()` et hors de tout `try`. Aucun chemin de levée n'y est atteignable
  aujourd'hui — et c'est justement pour ça qu'il faut l'écrire : la contrainte « cette première
  instruction ne lève pas » est désormais dans le KDoc, à relire si la sonde change.
- **Le `sensor` de la première ligne était gonflé.** Le rappel de capture compte dès que la session
  caméra tourne, donc avant la première image livrée à l'analyse ; la première fenêtre héritait de
  tout ce retard. Le compteur est remis à zéro à son ouverture. Sans effet sur les agrégats — cette
  fenêtre est de la chauffe — mais c'était un chiffre faux dans une ligne qu'on recopie.
- **Le plan et le code ne nommaient pas la même colonne** (`drop_noimg` contre `drop_other`), et
  `lat_n` existait dans le code sans figurer au tableau. Quelqu'un qui prépare son script de
  dépouillement d'après ce plan aurait cherché une colonne absente de 120 lignes. Les deux sont
  alignés.

Les constats de rigueur sont suivis aussi : `NANOS_PER_MILLI` était déclaré trois fois dans le même
package et vit maintenant dans `Nanoseconds.kt` ; le `coerceIn` de `percentileIndexOf` était du code
mort qui aurait levé une exception sans rapport s'il avait été atteint, et la précondition est
écrite à sa place ; `viewModel` est devenu une clé de l'effet thermique ; et trois affirmations trop
nettes — l'invariant des compteurs, « la dernière image de la fenêtre », « aucune allocation » — sont
corrigées plus bas, là où elles étaient fausses à la marge.

Un constat est **noté et non corrigé** : `drop_other` fusionne trois causes (fermeture en cours,
image sans tampon, dimension refusée). Voir les limites connues.

### Décisions qui viennent de toi

- **Mesurer d'abord, et ne corriger que ce que la mesure désigne.** Si la référence tient tous les
  critères, on n'optimise rien et le ticket se clôt sur la mesure et l'explication. Pas
  d'optimisation fabriquée pour justifier un ticket.
- **L'instrumentation seule dans son propre commit**, sans aucune optimisation, et un arrêt complet
  avant tes relevés.
- **Le protocole d'appareil vit ici**, dans ce plan, et non dans un fichier `tests/` : il n'y a
  jamais eu de dossier `tests/` dans ce dépôt, et les protocoles du S4 sont dans `plan-s4.md`.
- **La fenêtre se clôt à l'arrivée des images**, sur `bv-pose`, plutôt que par un battement qui
  posterait la clôture depuis le thread principal : zéro synchronisation, zéro risque de
  `RejectedExecutionException` pendant `close()`. L'angle mort qui va avec est écrit dans les
  limites connues.
- **Un dossier `metrics/`** plutôt que de faire parler `pose/` de débit et de thermique.
- **Agrégats séparés par état d'enregistrement**, parce qu'une série s'arrête seule à 900 images
  (≈ 90 s) : un run de 10 minutes avec enregistrement contient forcément des fenêtres sans
  enregistrement entre deux séries, et les mélanger rendrait l'écart `rec=0` / `rec=1` illisible.
  Une fenêtre ne compte en `rec=1` que si l'enregistrement l'a **couverte en entier**.
- **La chauffe part de la première inférence réussie**, pas de la création de la sonde : le temps de
  la permission ou de se placer dans le cadre ne doit pas manger les 30 secondes de chauffe ML Kit.
- **`am force-stop` entre deux scénarios**, pour que chacun reparte d'un agrégat vide et d'une
  chauffe neuve.

### Décisions qui viennent de moi

- **Un nouveau dossier `metrics/`**, avec les couches à l'intérieur comme les cinq autres
  fonctionnalités. Un instrument de mesure n'est pas une règle du produit : il a sa propre raison de
  changer, il observe une chaîne qui traverse `camera/` **et** `pose/`, et il se retire d'un seul
  dossier le jour où il ne sert plus.
- **Tout le calcul dans `metrics/domain`**, donc du Kotlin pur testé sur la JVM : médiane, p95,
  débit sur la durée réelle, exclusion de la chauffe, agrégats, et **la ligne de journal elle-même**
  — c'est elle qui porte le piège de la locale, donc elle doit être éprouvable sans appareil.
- **La convention de percentile du spike, reprise à l'identique** : index `((n − 1) × p)` tronqué,
  sans interpolation. Donc sur un nombre **pair** de valeurs, la médiane est l'élément inférieur du
  milieu et non la moyenne des deux. Motif : les 26,4 ms de SCRUM-22 ont été produits par cette
  convention (`RunStatistics.percentile`, sur `origin/spike/mlkit-pose`), et deux conventions
  différentes rendraient les chiffres non comparables. L'écart entre les deux vaut au pire un
  intervalle d'échantillon, soit quelques dixièmes de milliseconde sur 150 valeurs.
- **`SystemClock.elapsedRealtimeNanos()`** et non `System.nanoTime()` qu'utilise le battement du
  `ViewModel` : c'est l'horloge qui a produit les 26,4 ms du spike.
- **Le rappel de capture camera2 voyage dans `FrameAnalysis`**, qui devient « ce que le cas d'usage
  d'analyse demande à l'application » : l'analyseur, son exécuteur, et l'écoute de capture.
  L'alternative était un neuvième paramètre à `bind()`, qui en a déjà huit, plus un paramètre de
  plus à traverser dans `CameraPreview` pour un objet qui appartient de toute façon au même
  propriétaire que l'analyseur.
- **L'état thermique est observé par l'écran et poussé dans le `ViewModel`**, exactement comme le
  dossier de télémétrie et les objectifs disponibles : `PowerManager` demande un `Context`, que le
  `ViewModel` n'a pas et ne doit pas avoir.
- **Le chemin de sortie anticipée de `analyze()` est séparé en deux tests explicites**, pour
  distinguer une image jetée par le portillon d'une image sans tampon. Aujourd'hui une seule
  condition à court-circuit ne permet pas de savoir laquelle a joué, et les deux ne veulent pas dire
  la même chose du tout.

### Où une image peut se perdre, du capteur à l'écran

Huit étapes, lues dans le code et dans les sources de CameraX 1.4.2, pas de mémoire. Les deux pertes
**silencieuses** sont les étapes 2 et 3 : elles sont invisibles aujourd'hui, et c'est tout le sujet.

| # | Étape | Thread | Coût par image | Perte possible |
|---|---|---|---|---|
| 1 | Capteur et exposition automatique | HAL caméra | — | cadence réduite si l'exposition s'allonge |
| 2 | `ImageReader` de l'analyse, profondeur 4 | exécuteur caméra CameraX | ~0 | **silencieuse** |
| 3 | Cache d'une image de `KEEP_ONLY_LATEST` | exécuteur caméra CameraX | ~0 | **silencieuse** |
| 4 | Livraison à `bv-pose` | caméra → `bv-pose` | 4 petites allocations | non |
| 5 | Portillon et `process()` | `bv-pose` | **26,4 ms** (SCRUM-22) | portillon, comptée |
| 6 | `toPoseFrame`, `tryEmit`, `close()` | `bv-pose` | une map de 33 entrées | non |
| 7 | Collecteurs du `SharedFlow` | principal et `Default` | voir plus bas | une pose, jamais une image |
| 8 | Dessin de l'overlay | principal | ≤33 projections, ~68 primitives | une image **affichée** |

**Étape 1 — le capteur.** La cadence est celle de la requête répétée de la session. Si l'exposition
choisie par l'exposition automatique dépasse la période d'image, le HAL descend à la borne basse de
`CONTROL_AE_TARGET_FPS_RANGE` : en pièce sombre, une plage `[15, 30]` devient 15 images par seconde,
et **aucune optimisation logicielle ne rattrape ça**.

**Étape 2 — le tampon de l'`ImageReader`.** `ImageAnalysis.java:193` fixe
`NON_BLOCKING_IMAGE_DEPTH = 4`, retenu dès que la stratégie n'est pas `BLOCK_PRODUCER`
(`ImageAnalysis.java:355-357`). À chaque image disponible,
`ImageAnalysisNonBlockingAnalyzer.java:63-66` appelle **`acquireLatestImage()`**, qui ferme toutes
les images de la file sauf la plus récente. Première perte silencieuse.

**Étape 3 — le cache d'une image.** `ImageAnalysisNonBlockingAnalyzer.java:75-116` : tant que
*notre* image est ouverte (`mPostedImage != null`), l'image qui arrive est mise en cache et celle
qui y était est **fermée** (lignes 91-95) ; une image en retard d'horodatage est fermée directement
(84-89). Quand nous appelons `imageProxy.close()`, `analyzeCachedImage()` (131-140) relance celle du
cache. **C'est pourquoi le portillon n'est jamais contendu** : CameraX ne livre jamais deux images à
la fois. Le KDoc de `MlKitPoseSource` l'affirmait déjà ; la mesure le prouvera, et un `drop_gate`
non nul voudrait dire que cette lecture est fausse.

**Étape 4 — la livraison.** `ImageAnalysisAbstractAnalyzer.java:168-292`. Avec nos réglages — YUV
par défaut, pas de rotation de sortie — aucune conversion ni copie n'a lieu : `convertYUVToRGB`
(214-221) et `rotateYUV` (227-239) sont hors d'atteinte, et `applyPixelShiftForYUV` (224-226) dépend
de `OnePixelShiftQuirk`, dont la liste d'appareils (`YuvImageOnePixelShiftQuirk.java:36-37` :
Motorola MotoG3, quatre Samsung, Xiaomi Mi A1) n'inclut pas le Honor 400. L'image d'origine est donc
enveloppée telle quelle et notre analyseur est appelé **sur notre exécuteur** (lignes 246-284, la
soumission ligne 270).

Détail noté en passant, sans effet sur le débit : en portrait, `isYuvRotationOrPixelShift` est vrai
(`ImageAnalysis.java:381-383`, rotation relative ≠ 0), donc un second `ImageReader` est alloué
(388-395) — environ 1,8 Mo — pour n'être jamais utilisé, puisque les tampons de rotation restent
nuls sans rotation de sortie. C'est de la mémoire, pas du temps.

**Étape 5 — nous.** `MlKitPoseSource.analyze` sur `bv-pose` : portillon, `uprightImageSizeOf`,
`InputImage.fromMediaImage` (une enveloppe, pas une copie), `detector.process`. Une image qui arrive
portillon fermé est fermée et abandonnée : perte **comptée**, et la seule que nous voyions
aujourd'hui.

**Étape 6 — le retour.** L'écouteur de succès tourne sur `bv-pose`, l'exécuteur étant passé
explicitement : `toPoseFrame` construit une map de 33 entrées, `analyzedFrameCount` avance,
`tryEmit` publie. L'écouteur de complétion rend le portillon **puis** ferme l'`ImageProxy`, ce qui
déclenche la livraison suivante. **Le cycle complet est donc : latence d'inférence + conversion +
`close()` + redélivrance.** C'est là que se lit l'écart entre 1/26,4 ms = 37,9 images par seconde en
théorie et les 29,4 observés : il reste ≈ 7 ms par tour en dehors de `process()`.

**Étape 7 — les collecteurs.** `tryEmit` sur un `SharedFlow` en `replay = 1` et `DROP_OLDEST` ne
suspend **jamais** : un collecteur lent perd des poses, il ne ralentit pas l'analyse. C'est
précisément ce qui fait des 23,5 une borne basse. L'enregistrement collecte sur
`Dispatchers.Default` : une comparaison d'horodatage par image, et une copie de 33 points une fois
sur trois.

**Étape 8 — l'affichage.** L'overlay lit la pose dans la **phase de dessin**, donc une nouvelle pose
n'invalide que le dessin ; l'écran ne se recompose qu'une fois par seconde, pour les compteurs. Un
thread principal chargé perd des images **affichées** sans changer le débit d'analyse — et
réciproquement, `bv-pose` saturé fait tomber le compteur FPS sur une interface restée fluide.

### Compter les images du capteur : vérifié dans les sources de camera-camera2 1.4.2

Pas de supposition : les jars de sources du cache Gradle ont été lus, comme en SCRUM-36. La chaîne
complète, maillon par maillon :

1. `Camera2Interop.Extender.setSessionCaptureCallback` range le rappel dans
   `SESSION_CAPTURE_CALLBACK_OPTION` (`Camera2Interop.java:197-202`).
2. `ImageAnalysis.Builder` est bien un `ExtendableBuilder` : il implémente `UseCaseConfig.Builder`
   (`ImageAnalysis.java:1070-1074`), qui étend `ExtendableBuilder<T>` (`UseCaseConfig.java:339-340`).
3. `Camera2UseCaseConfigFactory` pose `Camera2SessionOptionUnpacker.INSTANCE` sur **tous** les cas
   d'usage (`Camera2UseCaseConfigFactory.java:68`).
4. `Camera2SessionOptionUnpacker.unpack` emballe le rappel dans un `CaptureCallbackContainer` et
   l'ajoute par `addCameraCaptureCallback` (`Camera2SessionOptionUnpacker.java:85-88`), qui
   l'enregistre pour les requêtes **répétées et uniques** (`SessionConfig.java:634-648`).
5. `CaptureSession.issueRepeatingCaptureRequests` le reconvertit —
   `CaptureCallbackConverter.java:70-74` déballe le conteneur et rend le rappel camera2 d'origine —
   et le passe à `setSingleRepeatingRequest` (`CaptureSession.java:709-714`).
6. `SynchronizedCaptureSessionBaseImpl.java:397-405` appelle alors
   `setSingleRepeatingRequest(request, getExecutor(), listener)` : le rappel tourne sur
   l'**exécuteur caméra de CameraX**, ni le thread principal, ni `bv-pose`.

Donc `onCaptureCompleted` est appelé **une fois par requête répétée, c'est-à-dire par image que le
HAL a produite pour la session** — l'aperçu et l'analyse partagent la même requête. C'est exactement
le compteur qui manquait, et le `TotalCaptureResult` donne en plus :

- `CONTROL_AE_TARGET_FPS_RANGE` — la plage de cadence **réellement retenue** par l'exposition
  automatique ;
- `SENSOR_FRAME_DURATION` et `SENSOR_EXPOSURE_TIME` — la période d'image et l'exposition réelles,
  qui disent si le capteur a ralenti, et pourquoi.

Coût par image : un incrément atomique et trois lectures de clé, sur un thread qui n'est ni celui de
l'inférence ni celui de l'interface. Aucune dépendance nouvelle : `camera-camera2` 1.4.2 est déjà
déclaré dans `gradle/libs.versions.toml`, et `Camera2Interop` ne demande qu'un
`@OptIn(ExperimentalCamera2Interop::class)` — le même mécanisme que l'`@OptIn` d'`ExperimentalGetImage`
déjà en place dans `MlKitPoseSource`.

**Ce que ça ne change pas.** Aucune option de requête n'est posée, donc **aucun réglage de la caméra
n'est modifié** : c'est une écoute, pas une commande. Fixer la plage de cadence de l'exposition
automatique à 30 images par seconde serait une tout autre proposition, elle attendra son accord et
viendra avec sa contrepartie — image plus sombre en faible lumière, donc peut-être moins de points
détectés.

**Un filet, au cas où ce rappel ne se déclencherait pas sur l'appareil.** La fenêtre calcule aussi
`dt_min_ms`, le plus petit écart entre deux horodatages d'images **reçues**
(`ImageProxy.imageInfo.timestamp`, qui est `CaptureResult.SENSOR_TIMESTAMP` — voir `timestampMsOf`
dans `PoseLandmarkMapper`). Ce minimum est la période du capteur : la mesure tient donc même si
l'interop déçoit, et les deux chiffres se contrôlent l'un l'autre.

### L'instrumentation

**Une ligne par fenêtre de 5 secondes, sous l'étiquette `BvThroughput`, rien par image.** Tous les
nombres en `Locale.ROOT` : le téléphone est en français, et `0,523` rendrait les relevés impossibles
à recopier dans un tableur ou un script sans erreur.

| Champ | Ce qu'il dit | D'où il vient |
|---|---|---|
| `phase` | `chauffe` jusqu'à 30 s après la **première inférence réussie**, `mesure` ensuite | sonde |
| `t`, `win_ms` | instant et **durée réelle** de la fenêtre | `elapsedRealtimeNanos` |
| `sensor` | images produites par le capteur pour la session | rappel de capture camera2 |
| `recv` | images reçues par l'analyseur | entrée de `analyze()` |
| `drop_gate` | images jetées par le portillon | `compareAndSet` perdu |
| `drop_other` | abandonnées avant l'inférence pour une autre raison | sortie anticipée |
| `inf` | inférences réussies — **la même définition que les 29,4 du spike** | écouteur de succès |
| `fail` | inférences lancées qui ont **échoué** | écouteur d'échec |
| `fps` | `inf / win_ms`, jamais `inf / 5` | fenêtre pure |
| `lat_n` | nombre de latences derrière la médiane et le p95 | fenêtre pure |
| `lat_med_ms`, `lat_p95_ms` | latence du lancement de `process()` au succès | fenêtre pure |
| `dt_min_ms` | période du capteur, recoupement de `sensor` | horodatages reçus |
| `fdur_ms`, `expo_ms`, `ae` | période d'image, exposition, plage de cadence retenue | `TotalCaptureResult` |
| `thermal` | `NONE`, `LIGHT`, `MODERATE`… ou `NA` sous API 29 | `PowerManager` |
| `rec` | `0` aucune image enregistrée, `1` fenêtre **entièrement** couverte, `x` partielle | échantillonné par image |
| `w0_*`, `w1_*` | médiane, minimum et nombre des fenêtres hors chauffe, **par état d'enregistrement** | agrégat pur |

**`recv = drop_gate + drop_other + inf + fail`**, et c'est l'invariant qui rend la ligne lisible :
les quatre colonnes sont les quatre issues d'une image reçue, donc `sensor − recv` est exactement ce
que CameraX a jeté en silence. Deux réserves, écrites dans les limites connues, et aucune des deux
n'est un trou.

Les six dernières colonnes existent pour une raison pratique : les critères d'acceptation portent
sur la **médiane des fenêtres** et sur **la pire fenêtre** d'un run de 10 minutes, soit 120 lignes.
Les cumuler dans chaque ligne fait de la dernière ligne du journal le verdict du run, sans calcul à
la main. Et les tenir séparément pour `rec=0` et `rec=1` rend l'écart capture seule / enregistrement
lisible dans un seul run, alors qu'une série s'arrête d'elle-même à 900 images et laisse forcément
des fenêtres sans enregistrement au milieu. Une fenêtre à cheval sur un démarrage ou une fin de
série (`rec=x`) n'entre dans **aucun** des deux agrégats : elle décrirait un mélange.

**Coûts, et sur quel thread.**

- Rappel de capture, sur l'exécuteur caméra de CameraX : un `incrementAndGet` et trois `get()` dans
  le résultat. De l'ordre de la microseconde — avec deux `Long` emboîtés par image, puisque les clés
  du résultat rendent des types nullables : soixante petites allocations par seconde, sur un thread
  qui n'est ni celui de l'inférence ni celui de l'interface.
- `bv-pose`, par image : deux ou trois incréments de champs ordinaires et un `add` dans un tableau
  de `long` **préalloué** et réutilisé d'une fenêtre à l'autre, donc aucune allocation dans le
  chemin chaud.
- `bv-pose`, une fois par fenêtre : un tri de ≈150 `long`, un `String.format`, un `Log.i`. Une
  fraction de milliseconde toutes les 5 secondes.
- Thread principal : **rien par image**. Le seul ajout est l'écouteur thermique, qui ne parle que
  quand le système change d'état.

**Ce qui vérifie que rien d'autre ne tourne sur `bv-pose`.** Par le code : l'exécuteur n'est passé
qu'à `ImageAnalysis.setAnalyzer`, aux trois écouteurs de `Task` et à la tâche de `close()`, et à
rien d'autre dans tout le dépôt. Sur l'appareil : la sonde écrit **une seule fois**, à la première
image, une ligne nommant le thread du rappel de capture et celui de l'analyse, ce qui prouve la
séparation au lieu de l'affirmer. Et pour le thread principal,
`adb shell dumpsys gfxinfo fr.upjv.bodyvision` donne les images saccadées de l'affichage sans une
ligne de code en plus.

### Le découpage

```
metrics/domain/ThroughputWindow.kt        accumulateur d'une fenêtre, et ce qu'elle a vu
metrics/domain/ThroughputRunAggregate.kt  médiane, minimum et compte des fenêtres, par état
metrics/domain/Percentile.kt              la convention de percentile du spike, à un seul endroit
metrics/domain/Nanoseconds.kt             les deux conversions depuis la nanoseconde
metrics/domain/ThroughputLogLine.kt       la ligne, en Locale.ROOT
metrics/data/ThroughputProbe.kt           frontière : horloge, Atomic*, rappel camera2, journal
metrics/data/ThermalStatus.kt             libellé et observation (API 29+), gardés par SDK_INT
```

Les dépendances pointent vers le domaine, qui ne connaît ni Android, ni ML Kit, ni CameraX. Fichiers
existants touchés : `pose/data/MlKitPoseSource.kt` (la sonde au constructeur, quatre points de
comptage, `startNanos` dans `StartedInference`), `camera/data/FrameAnalysis.kt` (le rappel de
capture), `camera/data/CameraSessionBinder.kt` (l'`Extender`), `session/ui/LiveSessionViewModel.kt`
(possède la sonde, lui donne `recording.isRecording` comme source de vérité unique) et
`session/ui/LiveSessionScreen.kt` (l'écouteur thermique).

### Tests JVM

JUnit 4 seul, un comportement par test, tout sur du Kotlin pur. **63 au départ, 24 ajoutés, 87 en
tout.**

`ThroughputWindowTest`, 14 tests : médiane et p95 sur un nombre **impair** de latences ; médiane sur
un nombre **pair**, qui épingle la convention du spike — 20 et non 25 sur `10, 20, 30, 40` ; sur
**une seule** valeur ; **fenêtre vide** — aucune division par zéro, aucun `NaN`, aucun infini, pas
d'écart inventé ; **débit sur la durée réelle** — 29 images en 1 050 ms donnent 27,62 et non 29 ;
les cinq compteurs d'images rendus tels quels ; le **plus petit écart** entre deux images reçues
retenu parmi 33, 67 et 33 ; cet écart **pris en compte quand il enjambe deux fenêtres** ; une
**inférence qui échoue** comptée à part, sans latence, hors du débit, et l'invariant des quatre
issues vérifié ; les **quatre couvertures d'enregistrement** — aucune image enregistrée, toutes, une
partie seulement, et aucune image du tout ; une fenêtre échue seulement à partir de sa durée
nominale.

`ThroughputRunAggregateTest`, 7 tests : tant qu'aucune inférence n'a réussi, **tout** est de la
chauffe ; la chauffe dure trente secondes **après la première inférence réussie**, à la
milliseconde près ; une fenêtre de chauffe n'entre ni dans la médiane ni dans le minimum ; médiane
et minimum des fenêtres retenues ; **capture seule et enregistrement agrégés séparément** ; une
fenêtre à cheval sur un enregistrement dans **aucun** des deux ; aucune fenêtre retenue — pas de
`NaN`, compte à 0.

`ThroughputLogLineTest`, 3 tests : **`Locale.FRANCE` forcée**, une fenêtre connue redonne
**exactement** la ligne attendue, et la ligne ne contient aucune virgule — même méthode que
`SeriesUploadJsonTest` pour le JSON ; une fenêtre vide et un capteur muet donnent une ligne sans
`NaN` ni `Infinity`, où ce qui manque s'annonce `NA` et jamais `0` ; les trois symboles de
couverture, `0`, `1` et `x`.

Pas de test sur `ThroughputProbe` ni sur `ThermalStatus` : ils n'ont de comportement qu'avec un
`PowerManager` et un rappel camera2, et les simuler ne testerait que la simulation — même
raisonnement que pour `TelemetryFileWriter` au S4. Pas de test sur le `ViewModel` : il instancie
`MlKitPoseSource`, donc il n'est pas constructible sur la JVM (limite connue de SCRUM-36).

### Vérification

```powershell
.\gradlew.bat clean build test lint --rerun-tasks
.\gradlew.bat ktlintCheck --rerun-tasks
```

Relevé le 24/11/2026, après les correctifs de relecture : `BUILD SUCCESSFUL`, **87 tests, 0 échec**,
**0 erreur** lint pour **12 avertissements**, **0 violation** ktlint. Les 12 avertissements sont exactement ceux d'avant ce
ticket — `GradleDependency`, `AndroidGradlePluginVersion`, `OldTargetApi`, `NewerVersionAvailable` —
donc l'instrumentation n'en ajoute aucun, et la chaîne d'outils reste figée.

Un détail de compilation qui mérite d'être écrit, parce qu'il se représentera : `recording` porte
désormais son type explicitement dans le `ViewModel`. La sonde lit l'enregistrement, qui dépend de
la source de pose, qui reçoit la sonde — l'inférence de type tourne alors en rond et le compilateur
le dit mot pour mot (« Type checking has run into a recursive problem »). Le type écrit casse le
cycle sans rien changer à l'exécution.

### Les hypothèses que la mesure doit départager

Pour « environ 15 images par seconde sans personne », deux causes possibles, et **une seule ligne de
journal les sépare** :

| Hypothèse | `sensor` / 5 s | `fdur_ms` et `ae` | `lat_med_ms` | `inf` | `fail` |
|---|---|---|---|---|---|
| ML Kit relance la détection complète | ≈150 | 33,3 et `15-30` | **≈60** | ≈75 | 0 |
| Le capteur ralentit en faible lumière | **≈75** | **66,7** et `15-30` | ≈26 | ≈75 | 0 |
| ML Kit refuse une partie des inférences | ≈150 | 33,3 et `15-30` | ≈26 | ≈75 | **≈75** |
| Les deux premières à la fois | ≈75 | 66,7 | ≈60 | ≈40 | 0 |

La troisième ligne est celle que la relecture a rendue visible : sans la colonne `fail`, elle se
confondrait avec les deux premières à l'œil, et aucune des trois autres lignes n'y correspondrait.

D'où deux scénarios distincts dans le protocole : **pièce vide en lumière normale**, où seule la
première peut jouer, et **pièce vide en faible lumière**, où la seconde s'ajoute. Si c'est la
seconde, aucune optimisation logicielle n'y changera rien : ce serait un réglage de caméra, donc une
décision d'équipe.

Trois autres questions que la mesure tranche :

- **L'écart enregistrement / capture seule.** `w1_*` contre `w0_*` à `sensor` et `thermal` égaux. Et
  pour savoir si les 23,5 du 17/11 étaient le débit d'inférence ou seulement ce que le collecteur a
  reçu, on compare `inf` au total des images **reçues par la série**, que la ligne de
  `TelemetryRecording` donne déjà : gardées + écartées + hors échantillon.
- **Le portillon est-il vraiment jamais contendu ?** `drop_gate` doit rester à 0. Sinon la lecture
  des sources ci-dessus est fausse, et il faut le savoir.
- **Les ≈7 ms par tour hors `process()`** : `fps` nettement sous `1000 / lat_med_ms` les mesure sans
  code en plus.

### Protocole sur appareil

Honor 400 (DNY-NX9), Android 16, **appareil physique uniquement** : une mesure de performance sur
émulateur ne prouve rien.

#### Préparation

1. Binaire issu d'une porte complète verte, installé par `.\gradlew.bat installDebug`.
2. `adb logcat -G 16M`. **Ce n'est pas une précaution de style** : le tampon fait 256 Ko par défaut,
   le système émet deux lignes `RenderEngine` par image affichée, et la ligne cherchée est chassée
   avant d'être lue. C'est le piège qui a coûté la première série du 17/11.
3. Téléphone **froid** au départ, et laissé refroidir entre deux essais de 10 minutes. Noter l'état
   thermique de départ, qui doit être `NONE` ou `LIGHT`.
4. Rotation automatique active, téléphone en portrait, cadrage **trois-quarts** à deux ou trois
   mètres, luminosité de l'écran et lumière de la pièce notées.
5. Entre deux scénarios, dans cet ordre :

```bash
adb shell am force-stop fr.upjv.bodyvision   # agrégat vide, chauffe neuve
adb logcat -c                                 # journal vide
adb logcat -s BvThroughput                    # ne garde que l'étiquette de mesure
```

L'ordre compte : `force-stop` d'abord, sinon les dernières lignes du scénario précédent se
retrouvent dans le suivant.

**Et seulement si une colonne d'anomalie n'est pas à zéro** — `drop_gate`, `drop_other` ou `fail` —
refaire le même scénario avec les étiquettes de diagnostic, qui donnent la cause là où la colonne ne
donne que le compte :

```bash
adb logcat -s BvThroughput MlKitPoseSource CameraSessionBinder
```

Pas pendant le relevé lui-même : ces deux étiquettes-là émettent sur des événements, pas sur une
cadence, mais autant garder le journal de mesure propre.

#### Les cinq scénarios

Chacun dure **10 minutes**, et les **30 premières secondes sont exclues** — les lignes `phase=chauffe`
les écartent déjà des colonnes cumulées, qui n'agrègent que `phase=mesure`.

| # | Scénario | Attendu | OK / KO |
|---|---|---|---|
| 1 | Personne cadrée en trois-quarts, caméra arrière | `w0_med_fps` ≥ 28, `w0_min_fps` ≥ 24 | |
| 2 | Personne cadrée, **avec enregistrement** | l'écart `w1` / `w0` s'explique | |
| 3 | Pièce vide, lumière normale | débit **expliqué**, pas de seuil | |
| 4 | Pièce vide, **faible lumière** | débit **expliqué**, pas de seuil | |
| 5 | Personne cadrée, **caméra frontale** | `w0_med_fps` ≥ 28, `w0_min_fps` ≥ 24 | |

**Scénario 2, précision qui change le relevé** : une série s'arrête **toute seule à 900 images**,
soit environ 90 secondes. Il faut donc **relancer l'enregistrement à chaque fin de série** pendant
les 10 minutes — l'indicateur « ● Enregistrement » disparaît de lui-même et le bouton revient sur
« Démarrer ». On obtient ainsi six ou sept séries, et les fenêtres `rec=1` les couvrent ; les
fenêtres entre deux séries sont des `rec=0`, et celles à cheval des `rec=x`, écartées des deux
agrégats. Garder aussi la sortie de `adb logcat -s TelemetryRecording` de ce scénario : c'est elle
qui donne le total des images reçues par chaque série.

Scénario 4 : pièce volets fermés, lumière éteinte, sans jamais pointer une source de lumière. Noter
la plage `ae` et `fdur_ms`, qui sont tout l'intérêt de l'essai.

#### Ce qu'on relève, pour chaque scénario

La **dernière ligne** de chaque run porte déjà les agrégats. À recopier dans le tableau :

| Scénario | `w0_med_fps` | `w0_min_fps` | `w0_n` | `w1_med_fps` | `w1_min_fps` | `w1_n` | `lat_med_ms` | `lat_p95_ms` | `sensor` / `recv` / `inf` | `drop_gate` / `drop_other` / `fail` | `ae` / `fdur_ms` / `expo_ms` | thermique début → fin |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | | | | — | — | — | | | | | | |
| 2 | | | | | | | | | | | | |
| 3 | | | | — | — | — | | | | | | |
| 4 | | | | — | — | — | | | | | | |
| 5 | | | | — | — | — | | | | | | |

Pour `lat_med_ms`, `lat_p95_ms`, `sensor`, `recv`, `inf` et les colonnes de caméra, prendre une
fenêtre `phase=mesure` **représentative** du milieu du run, et noter laquelle (son `t`). Ces
colonnes-là sont par fenêtre, pas cumulées. Pour les trois colonnes d'anomalie en revanche, relever
le **pire** cas du run : ce qu'on cherche à savoir est s'il y en a eu, pas combien en moyenne.

#### Critères d'acceptation

- Débit d'inférence médian **≥ 28 images par seconde** sur les fenêtres de 5 s, **aucune fenêtre
  sous 24**, scénarios 1, 2 et 5.
- Latence d'inférence médiane et p95 relevées.
- L'écart entre le scénario 2 et le scénario 1 **expliqué**, pas seulement constaté.
- État thermique relevé à chaque fenêtre, **au plus `MODERATE`** à 10 minutes.
- Scénarios 3 et 4 **mesurés et expliqués**, non soumis au seuil de 28.
- Le compteur FPS de l'écran reste comparable aux 29,4 de SCRUM-22 : même définition, les succès de
  `process()`, inchangée par ce ticket.

### Limites connues

**1. Plus aucune image reçue, plus aucune ligne.** La fenêtre se clôt à l'arrivée des images, sur
`bv-pose` : c'est ce qui permet de n'avoir aucun verrou et aucune tâche postée depuis le thread
principal. Si l'analyseur ne reçoit plus rien du tout, aucune ligne n'est écrite — l'absence de
ligne est alors elle-même l'information, et le compteur d'écran tombe à 0. Une interruption plus
courte, elle, est visible : la fenêtre suivante porte un `win_ms` allongé, et son `fps` est calculé
sur cette durée réelle.

**2. `drop_other` fusionne trois causes**, et la relecture a eu raison de le signaler : une
fermeture en cours, une image sans tampon `Image`, et une dimension refusée par `uprightImageSizeOf`
tombent dans la même colonne. Trois diagnostics sans rapport, donc. Choix assumé : les trois sont
des anomalies qui doivent valoir **zéro** sur un run sain, et trois colonnes de zéros rendraient la
ligne moins lisible pour un cas qui ne devrait pas arriver. La colonne dit qu'il y en a eu ; la
cause se lit dans les lignes étranglées de `MlKitPoseSource`, d'où la commande de diagnostic du
protocole. À séparer le jour où un relevé montre un `drop_other` non nul.

**3. L'invariant `recv = drop_gate + drop_other + inf + fail` a deux réserves.** Une image reçue à
la fin d'une fenêtre dont l'inférence réussit dans la suivante compte dans les deux : l'écart est
d'au plus une image par frontière, et il se compense d'une fenêtre à l'autre. Et à la fermeture du
détecteur, les `Task` en vol sont **annulées** — ni succès ni échec — ce qui ne concerne que la
dernière fenêtre d'une session, celle qu'on ne relève pas.

**4. La sonde n'est pas conditionnée à une variante de compilation**, donc elle tourne aussi en
release. Ses deux listes de débits grandissent d'un élément par fenêtre, soit 720 par heure : sans
importance pour un run de dix minutes, mais c'est une raison de la retirer ou de la borner quand
elle aura servi.

**5. Le rappel de capture compte ce que le HAL a complété.** Une image que le capteur produit et que
le HAL abandonne avant de compléter sa requête reste invisible, et `onCaptureFailed` n'est pas
compté. `sensor` est donc un plancher, recoupé par `dt_min_ms`.

**6. La couverture d'enregistrement est échantillonnée par image**, donc à ≈33 ms près : une série
démarrée au milieu d'une fenêtre peut être lue comme couvrant toute la fenêtre si elle a précédé sa
première image. Sans conséquence sur les agrégats, qui écartent les fenêtres partielles.

**7. Notre latence et celle du spike ne se mesurent pas sur le même thread.** Dans le spike, les
écouteurs de `Task` tournaient sur le **thread principal** — aucun exécuteur n'était passé,
`MainActivity.kt:121` — donc les 26,4 ms de SCRUM-22 contiennent l'attente du thread principal. Ici
ils tournent sur `bv-pose`. À inférence égale, notre latence sera donc **un peu plus basse**, et la
différence est de la congestion d'interface qui n'avait rien à faire dans une mesure d'inférence.
Comparables, mais pas identiques : à ne pas lire comme un gain.

**8. `thermal=NA` sous API 29.** La **marge** thermique (`getThermalHeadroom`, API 30) n'est pas
relevée : elle est limitée à un appel toutes les 10 secondes et rend `NaN` au-delà, donc elle
remplirait une colonne de `NA` sur des fenêtres de 5 secondes.

**9. Un seul appareil.** Tout ce qui est mesuré ici vaut pour le Honor 400 et ne dit rien d'un autre
téléphone.

### Hors périmètre, assumé

Repère de gravité ([SCRUM-113](https://bodyvision.atlassian.net/browse/SCRUM-113)), seuil de
`visibility` et filtrage des points peu fiables, alertes de posture
([SCRUM-64](https://bodyvision.atlassian.net/browse/SCRUM-64)), réseau et file hors ligne (S6),
injection de dépendances, montée de version de la chaîne d'outils, écran de statistiques dans
l'application. Et le KDoc de `SeriesRecorder` qui annonce « de 15 à 30 images par seconde » : si la
mesure le démentit, il sera signalé, pas corrigé en passant.

### Résultats de mesure

*(Ajoutés ici après la séance sur appareil, avec leur date et leurs conditions.)*
