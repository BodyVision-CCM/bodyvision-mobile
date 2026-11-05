# Sprint S4 — afficher ce que la détection voit (SCRUM-36 → 37 → 38)

Ce fichier grandit ticket par ticket. Il ne remplace pas `docs/plan-s3.md`, qui reste le plan
du sprint précédent, tel qu'il a été exécuté.

---

## SCRUM-36 — Squelette filaire en surimpression

Branche `feat/scrum-36-squelette-overlay`, depuis `main` à `c2b6b6b`.

### Contexte

À la fin du S3, l'écran affiche l'aperçu caméra et deux compteurs, mais **personne ne collecte
le flux de poses** : la preuve que la détection tourne est un chiffre. Ce ticket dessine le
squelette par-dessus l'aperçu, ajoute la bascule arrière/frontale, et rend la projection juste
dans les deux orientations.

Deux choses manquaient pour ça :

1. `PoseFrame` ne portait pas la taille de l'image dans laquelle ses coordonnées sont
   normalisées. Sans elle, un `x` de 0,5 ne permet ni de projeter le point sur une vue recadrée,
   ni de savoir ce qu'un écart en x vaut par rapport au même écart en y.
2. `CameraSessionBinder` figeait `DEFAULT_BACK_CAMERA`, ne contraignait pas le format de l'aperçu,
   et alignait l'analyse sur une autre rotation que l'aperçu.

Un troisième correctif voyage avec eux sans être un manque de l'overlay, et autant le dire
franchement : `angleDegrees` faisait son `atan2` sur des coordonnées **anisotropes** — x divisé
par 480, y par 640 — donc l'angle était faux sur tout segment oblique. **Cette fonction n'a aucun
appelant en production** (vérifié : seuls son test et trois KDoc la citent), donc le correctif ne
change rien à ce que l'application fait aujourd'hui. Il est fait ici parce que `PoseFrame` devait
changer de toute façon, et que laisser un `atan2` connu faux dans le domaine en attendant S5
serait pire. Commit `fix:` à part, pour que ça se lise.

### Ce que la relecture a changé

Le sous-agent `revue-bodyvision` a rendu la première version **non fusionnable**, avec raison.
Trois défauts tenaient aux critères écrits du ticket :

1. **L'aperçu et l'analyse n'étaient pas alignés sur la même rotation** (point bloquant, détaillé
   plus bas). Défaut antérieur au ticket, que l'overlay rend visible.
2. **Un squelette périmé était dessiné à chaque retour d'arrière-plan** : l'arrêt de l'écran est
   la seule discontinuité qui ne défait pas la liaison, donc rien ne venait oublier la dernière
   pose.
3. **L'objectif enregistré n'était pas honoré au lancement** : la première liaison partait avant
   que les objectifs de l'appareil ne soient connus, d'où un battement de caméra arrière pour qui
   avait choisi la frontale — et l'écran « caméra indisponible » sur un appareil sans caméra
   arrière, c'est-à-dire exactement ce que le ticket interdit.

Les trois sont corrigés ici. Les constats de rigueur qui suivaient le sont aussi : la trace de
liaison rapporte ce qu'elle observe au lieu de ce qu'elle en déduit, les conversions de densité
sortent des boucles de dessin, un compte de points n'est plus publié pour une pose écartée, et un
commentaire qui invoquait un test inexistant est réécrit.

Un dernier constat a été tranché en faveur de la suppression : la relecture a montré que le test
qui justifiait une couture `poseFrameOf` dans le mappeur **n'éprouvait rien** — l'égalité de
taille y était vraie par construction, et le seul câblage qui pourrait se tromper vit dans
`Pose.toPoseFrame`, non testable sans appareil. La fonction et son test sont donc retirés, et
`toPoseFrame` construit `PoseFrame` directement. Un test qui ne peut pas échouer coûte plus cher
qu'il ne rapporte.

### Décisions qui viennent de toi

- **Oublier les poses après `unbind()`, pas au toucher du bouton.** C'est le défaut que j'avais
  laissé passer : au toucher, l'ancienne caméra est encore liée, et une image arrière arrivée
  entre le toucher et le démontage démarrerait avec la nouvelle génération — sa pose serait
  émise, puis dessinée en miroir.
- **Ordre des commits tel que chacun compile et passe ses tests** : le `refactor:` de la taille
  avant le `fix:` des angles, qui lit `frame.imageSize`.
- **Le repli sur la caméra arrière** quand l'objectif enregistré n'existe pas, ou n'est pas
  encore connu — jamais l'écran « caméra indisponible » pour ça.
- **La trace des résolutions retenues**, une par liaison, en avertissement si les deux rapports
  d'aspect diffèrent.
- `main` était à `c2b6b6b` et ma référence `origin/main` était périmée : je l'avais annoncé à
  tort comme inexistant.
- **Corriger la rotation ici** plutôt que de l'isoler dans un ticket, puisque c'est le critère
  d'acceptation de SCRUM-36, en assumant que `SurfaceRotation.kt` et ses 6 tests disparaissent
  avec la logique qu'ils testaient.
- **Attendre de connaître les objectifs** avant la première liaison, plutôt que de lier le choix
  enregistré et de replier après un échec : `lens` devient nullable dans l'état.

### Décisions qui viennent de moi

- `UprightImageSize` est **déplacé** dans `pose/domain` plutôt que remplacé par un nouveau type.
  Un seul type, un seul nom, et ce nom dit déjà l'invariant qui compte. Un `ImageSize` du domaine
  à côté aurait obligé à convertir à la frontière deux `Int` identiques.
- L'overlay, la projection et la liste des connexions vont dans **`pose/ui`** : c'est la couche
  présentation de la fonctionnalité « pose », comme `camera/ui` est celle de « camera ».
  `session/ui` les assemble, exactement comme il assemble déjà `CameraPreview`. L'overlay reçoit
  `isMirrored` et ne connaît ni objectif ni CameraX, sinon `pose/ui` dépendrait de `camera/`.
- Le helper de `PoseGeometryTest` reçoit une image **carrée** : les angles attendus y avaient été
  calculés dans un repère isotrope, donc aucune valeur attendue ne bouge. Tu as confirmé.
- `availableCameraLenses` est écrit à la main sur `addListener`, comme `CameraSessionBinder`,
  plutôt qu'avec `ProcessCameraProvider.awaitInstance` : cette extension existe en 1.4.2 mais
  passe par `androidx.concurrent.futures.await`, qui n'est pas une dépendance déclarée ici.
- Le repli d'objectif sort du `ViewModel` dans une fonction pure, `resolveCameraLens`, sur le
  modèle de `resolveCameraPermissionStatus` du S3 : c'est la logique à risque de la bascule, et
  le `ViewModel` n'est pas constructible sur la JVM puisqu'il instancie `MlKitPoseSource`.

### Les deux vérifications, dans les sources de CameraX 1.4.2

Pas de supposition : les sources de `camera-core`, `camera-view`, `camera-camera2` et
`camera-lifecycle` 1.4.2 ont été lues (jars de sources du cache Gradle).

**Même champ entre l'aperçu et l'analyse.** CameraX choisit **déjà** du 4:3 pour l'aperçu, deux
fois dans le même objet par défaut — `Preview.java:804-810` : `DEFAULT_ASPECT_RATIO =
AspectRatio.RATIO_4_3`, et un `DEFAULT_RESOLUTION_SELECTOR` en
`RATIO_4_3_FALLBACK_AUTO_STRATEGY` + `HIGHEST_AVAILABLE_STRATEGY`. Côté analyse,
`ImageAnalysis.java:1041-1046` a le même strategy, et `ResolutionSelector.java:63` montre que
c'est **le défaut du builder** : notre sélecteur d'analyse, qui ne fixe qu'une
`ResolutionStrategy`, reste donc en 4:3. Deux rapports d'aspect identiques ⇒ même zone du
capteur, ce que `ViewPort` sert justement à garantir quand ils diffèrent.

C'est rendu explicite en reproduisant **les deux** stratégies par défaut sur `Preview`. N'en
poser qu'une ne serait pas équivalent : le sélecteur est une option unique, celui de
l'application remplace celui du défaut (`UseCase.java:232-251`), et `HIGHEST_AVAILABLE_STRATEGY`
disparaîtrait en silence. Aucune levée à craindre : `Preview.Builder.build()` ne valide que la
configuration posée par l'application (`Preview.java:921-925` → `ImageOutputConfig.java:353-375`).

`ViewPort` + `UseCaseGroup` est écarté avec un motif : avec un `ViewPort`,
`ImageProxy.getCropRect()` n'est plus l'image entière, alors que nous passons l'image **entière**
à `InputImage.fromMediaImage`. Les coordonnées resteraient dans le repère de l'image complète
pendant que l'aperçu n'en montrerait qu'un recadrage, et l'overlay deviendrait faux à moins de
projeter aussi ce recadrage. Sans `ViewPort`, le `cropRect` de l'aperçu est l'image entière
(`Preview.java:408-415`), ce qui est exactement l'hypothèse de la fonction de projection.

**Bascule arrière / frontale.** `PreviewView` affiche la frontale en miroir par défaut, sans une
ligne de notre part. La chaîne : `DEFAULT_MIRROR_MODE = MIRROR_MODE_ON_FRONT_ONLY`
(`Preview.java:805`) → `isMirroringRequired()` rend `camera.isFrontFacing()`
(`UseCase.java:462-475`) → `shouldMirror()` pose le drapeau sur le `SurfaceEdge`
(`Preview.java:291-296`) → `PreviewTransformation.java:307-325` ajoute `preScale(-1, 1)` à la
matrice d'affichage, ou `(1, -1)` si la rotation est 90/270. Donc **un seul** miroir, et il est à
l'affichage : le tampon d'analyse n'est jamais retourné, et les landmarks restent dans le repère
de l'image.

`imageInfo.rotationDegrees` reste juste en frontale, demi-tour paysage compris :
`ImageAnalysis.setTargetRotation()` → `tryUpdateRelativeRotation()`
(`ImageAnalysis.java:548-552` et `814-820`) → `cameraInfo.getSensorRotationDegrees(…)`, implémenté
dans `Camera2CameraInfoImpl.java:211-224`, qui passe
`isOppositeFacingScreen = (LENS_FACING_BACK == lensFacing)` à
`CameraOrientationUtil.getRelativeImageRotation` — lequel applique `(capteur + cible) % 360` en
frontale contre `(capteur − cible + 360) % 360` en arrière (`CameraOrientationUtil.java:47-65`).
L'orientation de l'objectif est donc prise en compte par CameraX : la frontale ne demande aucun
traitement de rotation particulier. **Attention à ne pas en conclure que la source de rotation de
SCRUM-31 était bonne** — elle ne l'était pas, pour une raison indépendante de l'objectif, et c'est
le sujet du paragraphe suivant.

`PreviewView.scaleType` est fixé explicitement à `FILL_CENTER`, qui est déjà son défaut
(`PreviewTransformation.java:97`, et le javadoc de `setScaleType`), pour que la vue et la
fonction de projection ne puissent pas diverger en silence.

**Même rotation entre l'aperçu et l'analyse — le point le plus délicat du ticket.** La projection
suppose que l'image analysée est orientée comme l'aperçu. Or les deux ne tiraient pas leur
rotation de la même source :

- `PreviewView` aligne l'aperçu sur la **rotation d'affichage** dès que l'aperçu n'a pas de
  rotation cible explicite **ou** que son implémentation est une `SurfaceView`
  (`PreviewView.java:243-249`, puis `updateDisplayRotationIfNeeded` et
  `overrideWithDisplayRotation` lignes `735-739`). En mode `PERFORMANCE`, le défaut, c'est une
  `SurfaceView` : l'aperçu suit donc l'affichage **sans recours**, et poser
  `preview.targetRotation` n'y changerait rien.
- `ImageAnalysis.targetRotation` était écrit par l'`OrientationEventListener` de SCRUM-31, donc
  par l'**orientation physique** lue sur l'accéléromètre.

Les deux divergent dès que la **rotation automatique est désactivée**, ce qui est un réglage
courant : l'affichage reste en portrait, l'accéléromètre signale un quart de tour,
`rotationDegrees` change, `uprightImageSizeOf` échange les axes, et le squelette se dessine tourné
de 90° par rapport au corps visible — pendant toute la durée où la condition tient, sans aucun
message. En portrait aussi, il suffit d'incliner l'appareil au-delà de 45° pour que le squelette
bascule d'un quart de tour tout seul.

L'analyse suit donc maintenant la **rotation d'affichage**, par un
`DisplayManager.DisplayListener`. Le demi-tour que SCRUM-31 voulait couvrir le reste :
`ROTATION_90 → ROTATION_270` ne change pas la configuration, donc rien n'est relié, mais c'est
bien un changement de rotation d'affichage et `onDisplayChanged` le signale. `SurfaceRotation.kt`
et ses 6 tests disparaissent avec la logique qu'ils testaient : il n'y a plus de correspondance
orientation physique → `Surface.ROTATION_*` à faire.

**Ce que `unbind()` garantit.** `ProcessCameraProvider.unbindAll()` est synchrone et sur le
thread principal — `@MainThread`, `Threads.checkMainThread()`, puis
`mLifecycleCameraRepository.unbindAll()` sous verrou (`ProcessCameraProvider.kt:677-683`,
`LifecycleCameraRepository.java:359-367`). Et notre `unbind()` appelle `clearAnalyzer()` **avant**
lui, ce qui est le point décisif : la référence de l'analyseur est relue **au moment où chaque
image est traitée** (`ImageAnalysisAbstractAnalyzer.java:180-182`, dispatch conditionné ligne
`210`), et `ImageAnalysisNonBlockingAnalyzer.java:75-80` ferme l'image sans rien appeler quand il
est détaché. Aucune image acquise après `clearAnalyzer()` n'atteint donc notre analyseur.

Reste une fenêtre, étroite et assumée : une image **déjà soumise** à l'exécuteur d'analyse
quelques microsecondes avant `clearAnalyzer()` exécutera quand même `analyze()`, donc après
l'incrément de génération. Avec `STRATEGY_KEEP_ONLY_LATEST`, au moment du démontage il y a soit
une inférence en vol — ancienne génération, écartée — soit un exécuteur au repos, auquel cas la
soumission et l'entrée dans `analyze()` sont séparées de quelques microsecondes. Le symptôme
serait **une seule image** de squelette pendant la reconfiguration de la session caméra, donc sur
un aperçu qui ne montre pas encore le nouvel objectif. La fermer tout à fait demanderait
d'identifier la liaison d'origine de chaque image, ce que CameraX n'expose pas.

### Ce qui est écrit

```
pose/domain/UprightImageSize.kt    déplacé depuis pose/data : PoseFrame le porte
pose/domain/PoseFrame.kt           + imageSize
pose/domain/PoseGeometry.kt        écarts ramenés en pixels avant les atan2
pose/data/PoseLandmarkMapper.kt    UprightImageSize importé du domaine, taille dans la pose
pose/data/MlKitPoseSource.kt       discardPendingFrames() + gardien générationnel
pose/ui/ViewPoint.kt               un point en pixels de vue
pose/ui/PoseProjection.kt          projectOnView(...) : FILL_CENTER, pure, testée sur la JVM
pose/ui/PoseConnections.kt         POSE_CONNECTIONS, 35 paires en PoseLandmarkType
pose/ui/SkeletonOverlay.kt         le Canvas : collecte le flux, lit la pose au dessin
camera/data/CameraLens.kt          enum BACK/FRONT + conversion en CameraSelector
camera/data/AvailableCameraLenses.kt   les objectifs réels, via ProcessCameraProvider.hasCamera
camera/data/CameraSessionBinder.kt bind(..., lens), aperçu explicite, rotation d'affichage,
                                   trace par liaison
camera/data/SurfaceRotation.kt     SUPPRIMÉ : plus d'orientation physique à traduire
camera/ui/CameraPreview.kt         paramètre lens, scaleType explicite, rappel onCameraUnbound
session/ui/CameraLensChoice.kt     resolveCameraLens(...) : le repli d'objectif, pur et testé
session/ui/LiveSessionUiState.kt   + lens nullable, + isLensSwitchAvailable
session/ui/LiveSessionViewModel.kt choix dans le SavedStateHandle, bascule, repli, oubli
session/ui/LiveSessionScreen.kt    overlay dans le Box, bouton de bascule, attente des objectifs
res/values/strings.xml             libellés et descriptions d'accessibilité, en français
```

#### Le correctif des angles (commit `fix:` à part)

Les écarts sont multipliés par la largeur et la hauteur de l'image redressée avant les `atan2`.
Les trois règles de `null` ne bougent pas, ni le repli `360 − x`. Un facteur commun aux deux axes
n'aurait aucun effet sur un angle : seul le **rapport** largeur/hauteur compte.

Le test qui échoue avant : image 480x640, sommet au centre, les deux autres points à
(+100, +100) et (+100, −100) pixels — 90° vrais, **73,74°** avec l'ancien calcul. Les 12 tests
existants gardent leurs valeurs attendues : 9 ont des segments alignés sur les axes, donc
insensibles à l'anisotropie, et les 3 autres sont déclarés dans une image carrée, où le repère
est isotrope par construction.

#### La projection

`scale = max(vueL / imgL, vueH / imgH)`, débord centré. `max` et non `min` parce que
`FILL_CENTER` **remplit** la vue et rogne le débord, là où un `min` laisserait des bandes vides.
En miroir, `x` devient `1 − x` **avant** la mise à l'échelle, et nulle part ailleurs. Aucun
bornage : un point hors cadre se projette hors de la vue, parce que ML Kit continue d'estimer un
point sorti du cadre et que le ramener sur le bord poserait un poignet au milieu d'un segment.

#### L'overlay et les trois phases de Compose

La pose vit dans un `mutableStateOf(null, neverEqualPolicy())` local, lu **dans le lambda de
dessin** du `Canvas` et jamais en tête du composable : Compose suit composition → mise en page →
dessin, et un état lu dans la dernière phase n'invalide que la dernière. Trente poses par
seconde donnent donc trente redessins et **zéro recomposition**. `neverEqualPolicy` parce que la
politique par défaut comparerait deux `PoseFrame`, soit une map de 33 entrées trente fois par
seconde pour un résultat toujours faux — ce que `MlKitPoseSource` reproche déjà à un `StateFlow`.

La collecte passe par `repeatOnLifecycle(STARTED)` : rien ne tourne quand l'écran n'est pas
visible, et surtout le bloc est **rejoué** à chaque retour au-dessus de `STARTED`, donc la pose en
main est remise à `null` au retour d'arrière-plan comme à la bascule d'objectif. `flowWithLifecycle`
ne rejouait, lui, que la collecte interne — voir le point 3 de « pas de squelette périmé ».

**Pas de squelette périmé**, quatre discontinuités et un seul principe — le `replay = 1` sert à
survivre à une recomposition, jamais à une discontinuité du flux caméra :

1. **reprise caméra** : l'écran retire l'overlay de la composition, donc rien n'est dessiné sur un
   aperçu figé, et l'état local repart vide au retour ;
2. **bascule d'objectif** : `isMirrored` change, l'effet redémarre, la pose en main est jetée ;
3. **retour d'arrière-plan** : c'est la plus fréquente, et c'est celle que la relecture a trouvée
   manquante. L'arrêt ne défait pas la liaison — `CameraPreview` reste dans la composition, donc
   pas de `onDispose` — et `flowWithLifecycle` ne rejouait que la collecte, pas le corps de
   l'effet. La pose d'avant la veille était donc redessinée au retour, pendant les quelques
   centaines de millisecondes que CameraX met à réouvrir la caméra. Il a fallu les deux moitiés :
   `repeatOnLifecycle(STARTED)`, dont le bloc est rejoué à chaque passage, **et** un
   `discardPendingFrames()` sur `ON_STOP`, sans quoi la pose revenait par le cache de rejeu ;
4. **le cache de rejeu et les inférences en vol** : `discardPendingFrames()`, appelé par le
   `ViewModel` **après** `binder.unbind()` (via le rappel `onCameraUnbound` de `CameraPreview`,
   pour que `camera/ui` ne connaisse pas la source de poses), à l'entrée en reprise caméra, et à
   l'arrêt de l'écran. Une inférence commencée avant l'appel est comptée dans le FPS — elle a bien
   été analysée — mais ni sa pose ni son compte de points ne sont publiés. Le cas de la rotation
   est couvert du même coup : elle refait la liaison, et la pose précédente avait été calculée
   avec une autre rotation d'image.

#### La bascule d'objectif

Le choix vit dans le `SavedStateHandle` (clé `lens`), enregistré par son nom et relu par
comparaison de nom, sans `valueOf` : un état restauré portant un nom inconnu se traite par un
repli, pas par une exception au démarrage.

L'objectif **effectif** publié dans l'état sort de `resolveCameraLens`, une fonction pure, et il
n'est pas toujours le choix enregistré. Il vaut `null` tant que les objectifs de l'appareil ne
sont pas connus, et l'écran n'affiche alors rien — c'est ce qui fait que le choix enregistré est
honoré **du premier coup**, sans battement de caméra arrière, et qu'un appareil sans caméra
arrière ne tombe pas sur l'écran « caméra indisponible » le temps d'une première liaison. Rien
n'est réécrit dans le `SavedStateHandle` pendant cette ignorance : effacer le choix de quelqu'un
sur une ignorance momentanée le détruirait à chaque lancement. Une fois les objectifs connus, un
choix impossible est corrigé durablement, et ce qui est écrit est l'objectif **retenu** et non la
constante `BACK` — sur un appareil sans caméra arrière, `BACK` serait une valeur fausse à
rattraper à chaque lecture.

Un ensemble d'objectifs **vide** est la seule exception, et elle n'est pas dans la fonction pure
mais dans le `ViewModel` : une réponse arrivée et vide veut dire qu'il n'y a aucune caméra à lier,
donc l'écran « caméra indisponible » est juste, et il vaut mieux que l'attente muette d'un
objectif qui ne viendra jamais. « Réessayer » repart alors d'une vraie demande, parce que
l'indisponibilité est une clé de l'effet qui interroge CameraX. Ce chemin n'est pas couvert par un
test JVM : il vit dans le `ViewModel`, que `MlKitPoseSource` rend non constructible hors appareil.

La liaison est refaite par la clé `lens` du `DisposableEffect` de `CameraPreview`. Le
`MlKitPoseSource` et son détecteur appartiennent au `ViewModel` : la bascule ne les recrée pas,
donc **pas de nouvelle chauffe ML Kit**. Le bouton n'est proposé que si l'appareil a les deux
objectifs.

### Tests JVM — 38 au total (33 au départ, 11 ajoutés, 6 supprimés)

`PoseProjectionTest`, un comportement par test : même rapport d'aspect sans recadrage ; vue plus
large que l'image (recadrage haut et bas) ; vue plus haute (recadrage gauche et droite) ; le
centre de l'image au centre de la vue malgré le recadrage ; miroir (`x = 0,2` donne le même point
que `x = 0,8` sans miroir, et `y` ne bouge pas) ; point hors cadre projeté hors de la vue, non
borné.

`PoseGeometryTest` : l'angle droit oblique dans une image non carrée.

`PoseLandmarkMapperTest` est **inchangé** : ses 7 tests couvrent déjà l'échange des axes aux
quatre rotations et la normalisation dans ce repère. Que la taille arrive bien dans `PoseFrame`
n'est pas éprouvable sur la JVM, puisque ce câblage vit dans `Pose.toPoseFrame`, qui demande des
types ML Kit — et un test qui simulerait ces types ne testerait que la simulation. C'est à
l'overlay sur l'appareil de le montrer : une taille et des coordonnées venues de deux repères
différents donneraient un squelette visiblement aplati.

`CameraLensChoiceTest` : le choix enregistré gardé quand l'appareil l'a ; aucun objectif connu →
aucun objectif à lier ; un choix impossible → l'arrière ; un appareil sans caméra arrière →
l'objectif qui reste, et non un écran de panne.

`SurfaceRotationTest` (6 tests) **disparaît** avec `surfaceRotationFor` : il testait la traduction
d'une orientation physique en `Surface.ROTATION_*`, et cette traduction n'a plus lieu d'être.

Pas de test sur `POSE_CONNECTIONS` : une liste de constantes n'a pas de comportement, et un
oracle qui la relirait referait l'erreur qu'il surveille. Pas de test sur `CameraLens` →
`CameraSelector` : `CameraSelector` ne se construit pas sur la JVM.

### Commits

Cinq, et chacun compile et passe ses tests — c'est ce qui dicte l'ordre, le `fix:` des angles
lisant `frame.imageSize` :

1. `docs:` ce plan ;
2. `fix:` l'analyse alignée sur la rotation d'affichage, et `SurfaceRotation.kt` avec ses tests
   retirés ;
3. `refactor:` la taille redressée dans le domaine et dans `PoseFrame`, plus le helper de
   `PoseGeometryTest` en image carrée ;
4. `fix:` le correctif des angles et son test, qui échoue sur le commit 3 ;
5. `feat:` la projection, les connexions, l'overlay, la bascule d'objectif et leurs tests.

Le dernier est un seul commit et non deux : l'overlay et la bascule touchent les mêmes zones de
`CameraPreview.kt` et de `LiveSessionScreen.kt`, et les séparer demanderait d'écrire une version
intermédiaire de ces fichiers — un état qui n'a jamais existé, pour une frontière qui ne se lit
pas dans le résultat.

### Vérification

```powershell
.\gradlew.bat clean build test lint --rerun-tasks
.\gradlew.bat ktlintCheck --rerun-tasks
```

Attendu : `BUILD SUCCESSFUL`, 38 tests verts, **0 erreur** lint, **0 violation** ktlint. Les 12
avertissements de lint sont tous dans `libs.versions.toml`, `app/build.gradle.kts` et
`gradle-wrapper.properties` — `GradleDependency`, `AndroidGradlePluginVersion`, `OldTargetApi`,
`NewerVersionAvailable` — c'est-à-dire la chaîne d'outils volontairement figée. Ce n'est pas une
liste de tâches.

Puis, sur le Honor 400, appareil physique uniquement :

- squelette posé sur le corps en **portrait** et en **paysage**, y compris mains et pieds près
  des bords du cadre ;
- **rotation automatique désactivée**, puis appareil tourné d'un quart de tour. Attention à
  l'oracle, il n'est pas « le squelette reste sur le corps » : dans cette configuration l'aperçu
  lui-même montre la scène **couchée**, puisque `PreviewView` est aligné sur un affichage
  verrouillé en portrait. L'analyse étant alignée sur le même affichage, ML Kit reçoit aussi une
  personne couchée, et le modèle de pose attend un sujet debout. Le comportement attendu est donc
  **« 0 point » et aucun squelette**. Ce qui serait un échec, c'est un squelette **décalé de 90°
  par rapport au corps** : c'est ce que faisait la version d'avant le correctif ;
- bascule arrière ↔ frontale : miroir juste, et **aucune pose de l'ancien objectif** dessinée sur
  le nouveau ;
- rotation après bascule, demi-tour paysage → paysage inversé compris ;
- FPS en frontale, 2 à 3 minutes, 30 premières secondes de chauffe exclues, seuil 24 FPS. S'il
  n'est pas tenu, on le note sans toucher à la configuration ML Kit, qui est figée ;
- `adb logcat -s CameraSessionBinder` : une ligne par liaison, qui donne l'objectif lié et les
  deux résolutions. Un avertissement y annonce lui-même deux rapports d'aspect différents.

### Ce que je t'explique à ce ticket

Les trois phases de Compose et pourquoi lire la pose au dessin évite trente recompositions par
seconde ; `Canvas` et `DrawScope` ; pourquoi `FILL_CENTER` prend le `max` des deux échelles ;
pourquoi le miroir n'existe qu'à l'affichage ; `SavedStateHandle` contre `remember` contre
`rememberSaveable` ; et pourquoi des coordonnées normalisées anisotropes faussent un `atan2`.

### Limites connues

**1. Rotation automatique coupée et appareil tourné : la détection peut tomber.** On ne peut pas
avoir les deux à la fois. L'image que voit ML Kit est soit alignée sur l'écran — projection juste,
mais sujet couché, donc détection perdue — soit alignée sur la gravité — détection bonne, mais
squelette décalé de 90°. Ce ticket garde l'alignement sur l'affichage : **c'est un échec sans
donnée fausse**, « 0 point » et pas de squelette, là où un squelette décalé se présenterait comme
une mesure. C'est la règle « échouer bruyamment ».

À noter pour qui rouvrira ce code : la javadoc de CameraX (`ImageAnalysis.setTargetRotation`)
recommande un `OrientationEventListener` pour `ImageAnalysis`, précisément parce que l'orientation
d'affichage peut être verrouillée. On va donc sciemment contre ce conseil, et le KDoc de
`trackDisplayRotation` dit pourquoi : il faut le **même repère** que l'aperçu, sans quoi l'overlay
est faux dans tous les cas, y compris les plus courants.

**Ticket séparé à ouvrir** : garder l'analyse alignée sur la gravité et faire tourner la
**projection** du delta affichage ↔ gravité. C'est la seule façon d'avoir la détection *et* la
superposition dans cette configuration. Pas nécessaire tant que la rotation automatique est
active.

**2. Pliables forcés en `TextureView` : l'aperçu peut rester figé sur une rotation.**
`preview.targetRotation` n'est pas posé. Sans effet sur le chemin `SurfaceView`, celui du
Honor 400, où `PreviewView` suit l'affichage de toute façon
(`PreviewView.java:243-249`) — mais `shouldUseTextureView` force la `TextureView` sur les
appareils listés dans `SurfaceViewStretchedQuirk` et `SurfaceViewNotCroppedByParentQuirk`, les
pliables. Là, `mUseDisplayRotation` passe à `false`, l'aperçu reste sur la rotation lue à la
construction, et l'analyse suit l'affichage : au demi-tour paysage, le squelette se dessinerait à
l'envers de l'aperçu. Le correctif est d'écrire la rotation sur `preview.targetRotation` en même
temps que sur `analysis.targetRotation`, dans le même écouteur. Non fait ici, faute d'appareil pour
le vérifier.

### Constats de relecture notés et non corrigés

Gardés ici pour qu'ils ne se reperdent pas, aucun n'étant du périmètre de ce ticket :

- `CameraLensChoice.kt` — `else -> CameraLens.FRONT` est juste tant que l'enum a deux valeurs ;
  `availableLenses.first()` ne dépendrait pas du cardinal. À changer le jour où un troisième
  objectif apparaît, et ce jour-là le `when` entier se relit.
- `CameraLens` vit dans `camera/data` et remonte jusqu'à `LiveSessionUiState` : un type de la
  couche données dans un état d'écran, contre la règle « les dépendances pointent vers le
  domaine ». `pose/ui` évite ça proprement en ne recevant qu'un booléen `isMirrored`. Un
  `camera/domain/CameraLens.kt`, avec le `toCameraSelector()` resté en `camera/data`, ferait
  pareil.
- `CameraSessionBinder.bind(analysis: FrameAnalysis? = null)` — le défaut `null` n'a plus aucun
  appelant depuis que `CameraPreview` est le seul, et `trackDisplayRotation` ne tourne que si
  l'analyse existe. Du YAGNI antérieur au ticket, devenu du code mort.
- `unregisterDisplayListener()` refait un `getSystemService` au lieu de garder celui qui a servi à
  l'enregistrement : s'il rendait `null` au second appel, l'écouteur fuirait en silence.
- `availableCameraLenses` reprend sa continuation sur l'exécuteur principal, donc les deux
  `hasCamera` lisent camera2 **sur le thread d'UI**. Les caractéristiques sont déjà en cache après
  l'initialisation du fournisseur, donc ce n'est pas un gel, mais reprendre sur
  `Dispatchers.Default` serait plus juste.
- `lens == null` affiche un `Box` vide : au démarrage à froid, c'est un écran noir et muet le temps
  d'initialiser `ProcessCameraProvider`. Choix assumé — pas de battement de caméra — mais ce vide
  ne se distingue pas d'un plantage.
- Le `ViewModel` instancie `MlKitPoseSource`, donc il n'est pas constructible sur la JVM : toute
  sa logique reste hors des tests. La partie à risque de ce ticket en est sortie
  (`resolveCameraLens`), le reste demande un ticket dédié.

### Hors périmètre, assumé

Export JSON et télémétrie (SCRUM-37), réseau (S6), seuil de `visibility` et filtrage des points
peu fiables (S5), optimisation du FPS (SCRUM-41), affichage d'angles ou d'alertes (SCRUM-64),
base locale, injection de dépendances.
