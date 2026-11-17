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
- **rotation automatique désactivée**, puis appareil tourné d'un quart de tour. L'aperçu montre
  alors la scène **couchée**, puisque `PreviewView` est aligné sur un affichage verrouillé en
  portrait, et l'analyse reçoit exactement la même image. L'oracle est donc celui de partout
  ailleurs : **le squelette reste posé sur le corps**, couché avec lui. Constaté sur l'appareil le
  05/11/2026 — ML Kit détecte aussi une personne couchée. Ce qui serait un échec, c'est un
  squelette **décalé de 90° par rapport au corps** : c'est ce que faisait la version d'avant le
  correctif ;
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

**1. Rotation automatique coupée et appareil tourné : les coordonnées sont dans le repère de
l'écran et non dans celui de la gravité.** L'image que voit ML Kit est alignée sur l'affichage,
donc la projection est juste : vérifié sur le Honor 400, rotation automatique coupée et appareil
tourné d'un quart de tour, la personne couchée est **détectée** et le squelette reste posé sur son
corps.

**L'hypothèse écrite ici avant ce test était fausse** : on attendait « 0 point » et aucun
squelette, au motif que le modèle de pose veut un sujet debout. Il reconnaît aussi un sujet
couché. C'est l'appareil qui a tranché, et c'est à ça que le test servait.

Ce qui reste comme limite : dans cette configuration, les coordonnées de `PoseFrame` sont
exprimées dans le repère de **l'écran** et pas dans celui de la **gravité**. Aucun effet sur un
angle **entre deux segments**, qui ne dépend pas de la rotation du repère ; mais une règle qui
mesurerait une inclinaison **par rapport à la verticale** — le dos penché — y lirait un angle
faux.

À noter pour qui rouvrira ce code : la javadoc de CameraX (`ImageAnalysis.setTargetRotation`)
recommande un `OrientationEventListener` pour `ImageAnalysis`, précisément parce que l'orientation
d'affichage peut être verrouillée. On va donc sciemment contre ce conseil, et le KDoc de
`trackDisplayRotation` dit pourquoi : il faut le **même repère** que l'aperçu, sans quoi l'overlay
est faux dans tous les cas, y compris les plus courants.

**Ticket ouvert** : [SCRUM-113](https://bodyvision.atlassian.net/browse/SCRUM-113) — analyse
alignée sur la gravité, et projection qui compense le delta affichage ↔ gravité, pour avoir les
deux repères justes à la fois. À traiter avant toute règle d'alerte fondée sur la verticale.

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

---

## SCRUM-37 — Extraire les landmarks et générer le JSON de télémétrie

Branche `feat/scrum-37-json-telemetrie`, depuis `main` à `c564a68`.

### Contexte

SCRUM-36 a rendu les poses visibles, pas conservables : elles sont dessinées puis jetées. Le jalon 1
demande la preuve que l'appareil produit de la télémétrie **conforme au contrat**. Ce ticket
enregistre une série à la demande, la sous-échantillonne à 10 FPS, l'encode en JSON conforme au
schéma `SeriesUpload` du contrat 0.4.1, et l'écrit dans un fichier du stockage propre à
l'application. Le fichier est validé contre le contrat **hors de ce dépôt**.

Aucun code réseau, aucune base locale, aucune dépendance nouvelle. La ligne « Format de télémétrie
— à implémenter en S6 » de `CLAUDE.md` est corrigée par ce ticket : la décision figée était « pas de
réseau avant S6 », pas « pas de JSON avant S6 ».

### Le contrat, et ce qu'il dit exactement

`BodyVision-CCM/bodyvision-contracts@main`, `openapi.yaml` **0.4.1**, schémas `SeriesUpload` et
`Frame`. Non recopié ici — une copie locale périme en silence. Ce qui a été lu et respecté :

- `SeriesUpload.required` : `batchId`, `index`, `exerciseCode`, `startedAt`, `endedAt`,
  `contractVersion`, `capture`, `frames` ;
- `capture.required` : `landmarkSet`, `model`, `analysisWidth`, `analysisHeight`, `nominalFps` ;
- `frames.maxItems: 900` ; `Frame.required` : `t`, `lm` ; `lm` : exactement 33 éléments de
  4 nombres, « dans l'ordre des indices `PoseLandmark` de ML Kit (0 à 32), l'ordre du tableau
  faisant foi », « trois décimales » ;
- `Frame.t` : « décalage en ms depuis `startedAt`. Strictement croissant. » ;
- `ExerciseCode` : motif `^[A-Z0-9_]+$`.

Le format du spike (`JsonFrameExporter` de SCRUM-23, objets nommés `{"type":…,"x":…}`) a servi à
**dimensionner** ce contrat et n'est pas ce contrat : il n'est pas repris.

### Ce que la relecture a changé

Le sous-agent `revue-bodyvision` a rendu la première version **non fusionnable**, et il avait
raison sur les deux points qui comptent. Les six correctifs, dans l'ordre de ce qu'ils coûtaient :

1. **Le demi-tour mettait deux repères dans un même fichier** (bloquant). `onDisplayChanged` était
   un quatrième chemin de discontinuité que personne n'avait vu, et la taille redressée ne change
   pas sur un demi-tour : rien ne pouvait le détecter, ni dans l'application ni en aval. Corrigé par
   un rappel depuis `trackDisplayRotation`, là où les trois autres discontinuités sont déjà
   traitées — et non en faisant descendre `rotationDegrees` jusqu'à l'accumulateur de télémétrie,
   qui est une information de caméra et n'a rien à faire dans le domaine de l'envoi.
2. **Le bouton retour perdait la série à chaque fois**, et la limite connue affirmait le contraire.
   Voir la limite n° 1, réécrite : l'hypothèse démentie y reste.
3. **Le sous-échantillonnage était un seuil et non une cadence.** Non signalé par la relecture, mais
   trouvé en écrivant les tests, et corrigé dans le même passage : voir « une échéance, pas un
   seuil ».
4. **Une levée dans la collecte laissait l'écran bloqué sur « Arrêter »** pour toujours, et la garde
   de `start()` refusait alors tout nouvel enregistrement. L'erreur est maintenant rattrapée,
   journalisée, et l'état remis à zéro.
5. **Le bouton ne faisait rien, sans le dire**, tant que le dossier d'écriture n'était pas connu. Il
   est désormais **inactif** : une action refusée doit se voir, et une ligne de journal ne se voit
   pas.
6. **Deux tests ne pouvaient pas échouer.** Le test de locale des horodatages passait sans
   `Locale.ROOT`, parce que le français est grégorien : il force maintenant une locale thaïe, qui
   écrit 2544 au lieu de 2001. Et le test « aucun caractère à échapper » laissait passer un
   guillemet au milieu d'une valeur, c'est-à-dire le seul cas qui compte : il compte maintenant les
   guillemets non échappés. Un test qui promet un filet et n'en tend pas est pire que pas de test.

Un constat a été **écrit plutôt que corrigé** : l'ambiguïté de `capture.analysisWidth`, qui est une
question pour le propriétaire du contrat et pas un défaut du code (limite n° 4).

### Décisions qui viennent de toi

- **Toute discontinuité du flux termine la série**, et pas seulement les deux cas du ticket
  (changement de taille d'image, bascule de caméra). Une série est une **fenêtre continue d'un seul
  repère d'image** ; un fichier qui porterait un trou de dix minutes dans ses `t`, ou deux repères
  à la suite, ne décrirait aucun mouvement réel. **Quatre chemins**, dont trois existaient depuis
  SCRUM-36 :
  1. `onCameraUnbound()` — liaison défaite : bascule d'objectif, quart de tour, sortie d'écran ;
  2. `onScreenStopped()` — passage en arrière-plan ;
  3. l'entrée en reprise caméra — plus aucune image livrée ;
  4. `onDisplayRotationChanged()` — **le demi-tour**, trouvé par la relecture. Paysage vers paysage
     inversé ne change pas la configuration, donc aucun des trois premiers chemins ne se déclenche,
     et la taille redressée est identique : la règle de taille ne peut pas le voir. L'analyse, elle,
     se met bien à rendre des coordonnées tournées de 180°. Le signal vient du
     `DisplayManager.DisplayListener` que SCRUM-36 avait déjà posé dans `trackDisplayRotation`, et
     qui ne rapportait rien à personne.

Les quatre chemins appellent **aussi** `discardPendingFrames()`, et le quatrième pour une raison qui
lui est propre : le `replay = 1` du flux sert la dernière pose à tout **nouveau collecteur**, et un
enregistrement démarré juste après un demi-tour en est un. Sans l'oubli, sa première image gardée
serait celle d'avant la rotation — celle qui fixe `startedAt` et le repère de toute la série. Le
raisonnement « l'overlay reste abonné, donc le cache n'est pas resservi » ne valait que pour
l'overlay.
- **`startedAt` est l'horloge murale lue à la première image *gardée***, et non au toucher du
  bouton. C'est ce qui fait coïncider `t = 0` avec `startedAt`, comme le contrat l'exige. Lue au
  toucher, elle serait antérieure de l'écart — une image si quelqu'un est déjà dans le cadre,
  beaucoup plus sinon — et la durée `endedAt − startedAt` annoncerait une série plus longue qu'elle
  n'est.
- **L'orchestration de l'enregistrement vit dans `telemetry/data/TelemetryRecording`**, possédée par
  le `ViewModel`, plutôt que dans le `ViewModel` lui-même : la logique d'enregistrement reste dans
  sa fonctionnalité, et `LiveSessionViewModel` garde trois rôles au lieu de quatre.
- **`isRecording` repasse à `false` dès que la série est close**, quoi que l'ait closée — bouton,
  discontinuité, taille d'image ou 900e image. L'écriture du fichier est la queue de l'opération,
  pas sa fin : la montrer comme un enregistrement en cours laisserait croire qu'on capte encore.
- **`contractVersion` vaut `1.0`**, tranché par le propriétaire du contrat : le champ porte la
  version du **format de télémétrie** — la structure de `SeriesUpload` et de `Frame` — et non
  `info.version` du document OpenAPI. Les deux numéros vivent dans le même fichier de contrat et ne
  bougent pas ensemble : le format en est à sa première version, le document est en `0.4.1` et
  continuera d'avancer sans que la constante change. Elle ne s'incrémentera que si la structure de
  `SeriesUpload` ou de `Frame` change. Constante unique, et aucun littéral de cette valeur ailleurs,
  pas même dans les tests.

### Décisions qui viennent de moi

- **Une fonctionnalité `telemetry/`**, avec `domain/` pur et `data/` pour Android, ML Kit, les
  coroutines et le disque — le même découpage que `pose/` et `camera/`. Tout ce qui décide est dans
  `domain/`, donc testable sur la JVM ; `data/` ne fait que brancher.
- **L'ordre des 33 points est *dérivé* de la table du mappeur**, jamais réécrit :
  `landmarkTypesByMlKitType.toSortedMap().values.toList()`. Une seconde liste écrite à la main
  serait une seconde source de vérité, et son erreur serait invisible dans un fichier de 280 Ko.
  Jamais `ordinal` : le domaine dit que l'ordre de déclaration de son enum n'a aucune signification.
- **L'accumulateur reçoit son horloge** (`() -> Long`) au lieu d'appeler
  `System.currentTimeMillis()`. Sans ça, le test « cette petite série redonne exactement cette
  chaîne » ne pourrait pas exister. Mocker l'horloge, c'est mocker une frontière, pas la logique.
- **`SeriesRecorder` est à usage unique** : une fois clos, `accept` rend `CLOSED` et n'ajoute plus
  rien. Un accumulateur recyclé par erreur ne produirait pas une série mélangée, il ne produirait
  rien — un défaut bruyant plutôt qu'un fichier faux.
- **`SimpleDateFormat` et non `java.time`** : `minSdk 24`, et aucun desugaring de la bibliothèque
  standard n'est déclaré. `Instant`/`DateTimeFormatter` donneraient une **erreur** lint `NewApi`, et
  l'activer demanderait la dépendance `desugar_jdk_libs`, interdite ici.
- **Aucune image gardée ⇒ aucun fichier.** Sans image gardée il n'y a ni taille d'image pour
  `capture`, ni `startedAt` — tous deux requis. Le journal le dit, avec le compte d'images écartées.
- **Le dossier d'écriture vient de l'écran**, pas d'un `Context` dans le `ViewModel` : le même motif
  que `onAvailableLensesObserved` de SCRUM-36. Le `ViewModel` ne garde qu'un `File`.

### Les quatre règles de sélection, dans l'ordre où elles s'appliquent

L'ordre décide de ce qui est compté, donc il est écrit :

1. **taille redressée différente de celle de la série** → la série se termine **sur l'image
   précédente**, celle-ci n'est pas examinée. La taille est figée par la première image *vue*, pas
   par la première gardée : elle vient de la caméra et ne dépend pas de la présence de quelqu'un
   dans le cadre. Tout le fichier a donc la taille annoncée dans `capture` ;
2. **pas due** — l'horodatage n'a pas atteint l'échéance d'échantillonnage → hors échantillon ;
3. **les 33 points ne sont pas tous là, ou une valeur n'est pas finie** → écartée et **comptée**.
   Rien n'est inventé : pas de zéro, pas de répétition du point précédent ;
4. **gardée.** À la première : `startedAt = horloge()` et `t = 0`. Ensuite `t` = écart en
   millisecondes avec l'horodatage de la première gardée. À 900 images, la série se termine.

**Le sous-échantillonnage : une échéance, pas un seuil.** C'est la distinction qui décide du débit,
et elle n'est pas intuitive — la première version de ce ticket s'y est trompée.

Sous-échantillonner **par le temps** et non « une image sur trois » va de soi : le débit réel varie
de 15 à 30 par seconde selon la lumière et la chauffe, donc un pas fixe donnerait tantôt 5, tantôt
10 images par seconde. Mais « au moins 100 ms depuis la dernière gardée » est un **seuil**, et un
seuil dérive : sur une grille de 33 ms, trois images ne font que 99 ms, il en faut donc quatre, et
le débit tombe à **7,6 par seconde** pour un `nominalFps` qui annonce 10 — 24 % d'écart, dont le
serveur tirerait des vitesses fausses. À 34 ms, la cadence mesurée de l'appareil, le même seuil
donne 9,8. Un millième de seconde d'écart à l'entrée, un quart du débit à la sortie : ce n'est pas
une règle, c'est un hasard.

La règle retenue est donc une **échéance** : `nextSample` est posée à la première image gardée, puis
avance de **100 ms exactement** à chaque image gardée — jamais de « maintenant + 100 ms ». Une image
est gardée si son horodatage a atteint l'échéance. On garde donc la première image passé chaque
multiple de 100 ms, le débit vaut 10 par seconde à une image près quelle que soit la période
d'entrée, et deux tests l'épinglent à 33 et à 34 ms.

L'échéance se **recale** dans un seul cas, et il en faut deux pour la justifier :
- après un trou du flux — personne dans le cadre pendant cinq secondes, donc aucune image gardée
  pendant que le temps avançait. Sans recalage, l'échéance resterait en retard et garderait *toutes*
  les images au retour de la personne, le temps de rattraper : une rafale à 30 par seconde au milieu
  du fichier ;
- quand l'échéance tombe **exactement** sur l'horodatage gardé. Le recalage garantit alors que
  l'échéance rendue est toujours strictement supérieure à l'horodatage gardé, donc qu'une seconde
  image au même horodatage n'est jamais due. C'est ce qui rend `t` **strictement croissant** sans
  second test — y compris pour un horodatage qui recule.

Ce que l'échéance **garantit**, et qui est plus fort qu'une moyenne : elle n'avance que de 100 ms
par image gardée et ne recule jamais, donc le nombre d'images gardées sur une durée *D* est majoré
par `D / 100 + 1`. Le débit ne peut donc **jamais** dépasser 10 par seconde sur la durée d'une
série, et 900 images couvrent 89,9 s sur une grille de 33 ms. `nominalFps: 10` passe ainsi d'une
approximation à une borne honorée.

Contrepartie assumée : quand une image arrive **en retard** sur sa tranche, la suivante revient sur
l'horaire plutôt que de décaler tout le reste, donc l'écart entre deux images gardées peut descendre
jusqu'à **une période de livraison d'entrée**, soit ≈33 ms sur l'appareil cible. La règle est
mécanique : une image gardée qui dépasse son échéance de *d* millisecondes, avec *d* < 100 donc sans
recalage, laisse `100 − d` jusqu'à la suivante — un hoquet d'inférence sous charge thermique qui
retarde une livraison de 67 ms suffit. Le vecteur de test, lui, descend à 60 et 70 ms : ce sont ses
nombres, pas la borne. Les `t` restent justes et strictement croissants — chaque image porte le
sien — et le débit global reste plafonné : seule la régularité de l'échantillonnage est perdue. La
règle du seuil garantissait l'inverse : des écarts réguliers, et un débit faux.

**Deux compteurs, pas un.** À 30 FPS, deux images sur trois sont normalement hors échantillon : les
compter comme « écartées » noierait le seul chiffre qui signale une anomalie. Le journal donne les
trois nombres séparément.

**900 images = 90 secondes** à 10 FPS nominal, un peu plus si des images sont écartées.

### L'encodeur, écrit à la main

`org.json` est vide dans les tests JVM — c'est un stub dans `android.jar` — et
kotlinx.serialization ajouterait un plugin à une chaîne d'outils figée. L'encodeur est donc du
Kotlin pur sur un `StringBuilder`, et il se défend ici parce que le schéma est **petit, plat et
figé** : huit champs, un objet `capture`, un tableau de nombres. Pour un schéma qui changerait
souvent, ce choix serait mauvais — chaque champ ajouté se paierait en code d'encodage et en test.

Deux pièges, et comment ils sont fermés :

- **La locale.** `String.format("%.3f", 0.5f)` rend `0,500` sur un téléphone en français, et une
  virgule décimale n'est pas du JSON : le fichier serait rejeté par le serveur sans que rien ne
  l'ait signalé sur l'appareil. Tous les nombres passent donc par
  `String.format(Locale.ROOT, "%.3f", …)`, et un test force `Locale.FRANCE` avant d'encoder pour le
  prouver.
- **`NaN` et infini** n'existent pas en JSON. Ils sont écartés à la règle 3, avant l'encodeur :
  `NormalizedLandmark` documente « fini » comme un invariant attendu, et ceci en est le dernier
  garde-fou, pas le premier.

**Aucune chaîne n'a besoin d'être échappée, et c'est structurel.** Les seules chaînes écrites sont
(a) des littéraux du code source — noms de clés, `SQUAT`, `1.0`, `MLKIT_POSE_33`, `BASE` — et
(b) deux valeurs que l'encodeur **fabrique lui-même** : l'UUID (`[0-9a-f-]`) et les deux
horodatages, produits par un motif de date fixe (`[0-9T:.Z-]`). Aucun texte venu de quelqu'un, d'un
fichier ou du réseau n'entre dans le JSON, et `exerciseCode` est une constante du code et non un
paramètre d'écran. Un test le vérifie par une expression régulière sur la sortie entière.

### Les fils d'exécution

- La **collecte** tourne dans `scope.launch` + `withContext(Dispatchers.Default)` :
  `viewModelScope` est sur `Dispatchers.Main.immediate`, donc sans ce dispatcher explicite la
  conversion des 33 points tomberait dix fois par seconde sur le thread d'interface.
- L'**écriture** est sur `Dispatchers.IO` : un thread qui attend le disque ne doit pas occuper un
  des rares threads de calcul.
- L'**arrêt venu de l'extérieur** fait `job.cancelAndJoin()` **avant** de lire l'accumulateur :
  c'est ce qui garantit que plus personne ne le touche pendant l'encodage, sans un seul verrou.
- **Sortir d'une collecte sans exception de contrôle** : `takeWhile { recorder.accept(it) == OPEN }`
  suivi de `collect()`. Quand la série se ferme d'elle-même, la collecte s'arrête et la ligne
  suivante écrit le fichier.
- L'écriture est enveloppée dans `NonCancellable` : la série est close et son fichier est la seule
  trace qui en reste.

### Ce qui est écrit

```
telemetry/domain/TelemetryContract.kt    les valeurs du contrat, en constantes commentées
telemetry/domain/TelemetryFrame.kt       t + 33 points ordonnés
telemetry/domain/TelemetrySeries.kt      la série terminée, immuable
telemetry/domain/OrderedLandmarks.kt     la map vers les 33 points, ou null si incomplète
telemetry/domain/SeriesRecorder.kt       l'accumulateur : garde, écarte, termine
telemetry/domain/IsoUtcTimestamp.kt      millisecondes epoch → ISO 8601 UTC, Locale.ROOT
telemetry/domain/SeriesUploadJson.kt     l'encodeur, à la main
telemetry/data/MlKitLandmarkOrder.kt     l'ordre des indices ML Kit, dérivé du mappeur
telemetry/data/TelemetryFileWriter.kt    écriture dans getExternalFilesDir("telemetry")
telemetry/data/TelemetryRecording.kt     l'enregistrement : batchId, collecte, écriture, journal
camera/data/CameraSessionBinder.kt       + onDisplayRotationChanged, et seulement sur un vrai
                                           changement de rotation
camera/ui/CameraPreview.kt               + le rappel de rotation d'affichage, passé à la liaison
session/ui/LiveSessionUiState.kt         + isRecording et isRecordingAvailable, deux scalaires
session/ui/LiveSessionViewModel.kt       possède l'enregistrement, le dossier, les quatre
                                           discontinuités
session/ui/LiveSessionScreen.kt          bouton, indicateur, et le dossier donné au ViewModel
res/values/strings.xml                   libellés, descriptions d'accessibilité, en français
CLAUDE.md                                la ligne « Format de télémétrie » corrigée
```

### Tests JVM

JUnit 4 seul, et tout ce qui est testé est synchrone et pur.

`SeriesRecorderTest` : une entrée régulière à **34 ms** garde 10 images par seconde ; une entrée à
**33 ms** aussi — les deux côtés du seuil qui faisait tomber l'ancienne règle à 7,6 ; un flux
irrégulier ne garde qu'une image par échéance ; la première image est toujours gardée ;
`t` part de 0 et croît strictement ; un horodatage en double n'est pas gardé ; une image sans
personne est écartée et comptée ; une image à 32 points est écartée et comptée ; une coordonnée
`NaN` est écartée ; un `visibility` infini est écarté ; changement de taille → la série se termine
sur l'image précédente ; 900 images → la série se termine et la 901e n'est pas ajoutée ; aucune
image gardée → pas de série ; `startedAt` est l'horloge de la première image **gardée** ; un
accumulateur clos reste clos.

`MlKitLandmarkOrderTest` : 33 éléments sans doublon ; l'ordre est celui des indices ML Kit, vérifié
aux indices 0, 11, 12, 23, 24 et 32 **contre la table du mappeur** et non contre une liste réécrite.

`SeriesUploadJsonTest` : locale `FRANCE` forcée → des points décimaux et aucune virgule dans un
nombre ; trois décimales, règle d'arrondi épinglée ; une valeur hors cadre (1,25 et −0,1) gardée
telle quelle ; une petite série connue redonne **exactement** la chaîne attendue ; la sortie ne
contient que les caractères attendus **et** exactement 44 guillemets, tous de structure, aucune
barre oblique inverse — un guillemet entré dans une valeur en ferait 45.

`IsoUtcTimestampTest` : un instant connu donne la chaîne attendue ; locale **thaïe** (calendrier
bouddhiste, qui écrit 2544 au lieu de 2001) et fuseau non UTC forcés → la même chaîne. Pas
`Locale.FRANCE` : le français est grégorien et en chiffres arabes, donc le test passait même sans
`Locale.ROOT`.

**Pas de test sur `TelemetryRecording`, et c'est un choix assumé.** « Deux enregistrements
consécutifs donnent deux `batchId` et deux fichiers » est son comportement, mais l'éprouver
demanderait d'émettre dans un vrai `SharedFlow` vers un collecteur sur `Dispatchers.Default` et
d'espérer qu'il soit prêt : le test échouerait parfois pour une raison de synchronisation, pas pour
la raison qu'il annonce. Sans `kotlinx-coroutines-test` — dépendance nouvelle, donc interdite — il
n'y a pas de version honnête de ce test. Et sa version « domaine » (deux accumulateurs construits
avec deux `batchId` portent deux `batchId`) serait vraie par construction, exactement le test que la
relecture de SCRUM-36 a fait supprimer. À la place : le `batchId` et l'accumulateur sont des
**variables locales de `start()`**, donc il n'y a rien à réutiliser ; l'accumulateur clos refuse les
images suivantes, et ça se teste ; et deux enregistrements de suite sont vérifiés **sur l'appareil**.

Pas de test sur `TelemetryFileWriter` : il n'a de comportement qu'avec un disque Android, et le
simuler ne testerait que la simulation. Pas de test sur le `ViewModel` : `MlKitPoseSource` le rend
non constructible sur la JVM — limite connue de SCRUM-36, et c'est pourquoi toute la logique à
risque est dans `telemetry/domain`.

### Vérification

```powershell
.\gradlew.bat clean build test lint --rerun-tasks
.\gradlew.bat ktlintCheck --rerun-tasks
```

Attendu : `BUILD SUCCESSFUL`, **0 erreur** lint, **0 violation** ktlint. Les avertissements
`GradleDependency`, `AndroidGradlePluginVersion`, `OldTargetApi` et `NewerVersionAvailable` restent :
chaîne d'outils figée, pas une liste de tâches.

Puis sur le Honor 400, appareil physique :

```bash
adb shell ls -l /sdcard/Android/data/fr.upjv.bodyvision/files/telemetry
adb pull /sdcard/Android/data/fr.upjv.bodyvision/files/telemetry/<batchId>.json .
# repli si l'accès de adb à Android/data est refusé par l'appareil :
adb exec-out run-as fr.upjv.bodyvision cat \
  /sdcard/Android/data/fr.upjv.bodyvision/files/telemetry/<batchId>.json > serie.json
```

- une série de 10 squats (≈ 30 s) : environ 300 images, soit **10 par seconde et non 30** ;
  33 points par image ; `t` de 0 à ≈ 30 000, strictement croissant ; **≈ 280 Ko**, ce qui recoupe
  les 0,26 Mo que le contrat annonce pour 300 images ;
- **critère d'acceptation des images écartées** : sur 10 squats cadrés en trois-quarts, **moins de
  1 %**, soit au plus 3 sur 300. Au-dessus, on s'arrête : ça voudrait dire que ML Kit rend
  régulièrement moins de 33 points sur une personne qu'il détecte, ce qui change le périmètre de
  SCRUM-41 ;
- une série interrompue par un **quart de tour** : elle se termine, le fichier est écrit, le `t` de
  la dernière image dit où ;
- une série interrompue par un **demi-tour** — paysage vers paysage inversé, l'appareil retourné
  bout pour bout, rotation automatique active : elle doit se terminer **aussi**, alors que la taille
  d'image ne change pas. C'est le constat bloquant de la relecture, et c'est le seul cas qui ne se
  voit pas dans le fichier produit : si la série continuait, on y lirait une personne qui se
  retourne d'un coup au milieu.

  **Mécanisme déjà prouvé sur l'appareil**, le 13/11/2026, sans aucun geste : rotation automatique
  coupée, un enregistrement en cours, puis `settings put system user_rotation 1` puis `3`, ce qui
  est exactement la transition `ROTATION_90 → ROTATION_270`. La série s'est terminée
  (`Série vide, aucun fichier écrit : 98 image(s) écartée(s)`), le bouton est revenu de lui-même sur
  « Démarrer », l'indicateur a disparu — et **aucune ligne `CameraSessionBinder` n'a été émise**,
  donc la liaison caméra n'a pas été refaite : les trois premiers chemins n'ont pas pu se déclencher,
  et c'est bien le quatrième qui a terminé la série.

  Ce que le geste physique ajoute, et qu'il faut savoir avant de le lire : un retournement lent
  passe par le portrait, et le capteur enchaîne alors `90 → 0 → 270`. Le passage par 0 **change** la
  configuration, donc la liaison est refaite et c'est le chemin 1 qui termine la série. Le chemin 4
  n'est exercé que par un retournement assez franc pour que le gestionnaire de fenêtres résolve
  directement `90 → 270`. Les deux issues sont correctes ; seule la preuve par `adb` distingue
  laquelle a joué.

  Sur cet appareil, le demi-tour **portrait** n'existe pas : `mAllowAllRotations=false`, donc le
  capteur n'atteint jamais `ROTATION_180`. `mSeascapeRotation=ROTATION_270` est en revanche bien
  défini, et l'historique de rotation montre que le capteur l'atteint ;
- une série interrompue par une **bascule de caméra** : même chose, et jamais deux tailles ni deux
  objectifs dans un fichier ;
- **deux enregistrements de suite** : deux noms de fichiers et deux `batchId` différents, le `t` du
  second repart de 0, son nombre d'images correspond à sa seule durée. C'est la vérification qui
  remplace le test JVM impossible ;
- une série poussée jusqu'à **90 secondes** : elle se termine toute seule à 900 images, le fichier
  est écrit, et le bouton revient de lui-même sur « Démarrer » ;
- `adb logcat -s TelemetryRecording` : une ligne par série, avec le chemin, les images gardées, les
  écartées, les hors échantillon et la taille en octets ;
- aucune image, aucune vidéo sur le disque : seulement des coordonnées.

### Ce que je t'explique à ce ticket

Pourquoi une locale change le format d'un nombre et pourquoi c'est un piège du JSON ;
`Dispatchers.Default` contre `Dispatchers.IO`, et pourquoi l'écriture va sur l'un plutôt que
l'autre ; pourquoi on sous-échantillonne par le temps ; pourquoi l'horloge murale et l'horodatage
des images ne se mélangent pas ; ce que garantit un `batchId` aujourd'hui et ce qu'il ne garantira
qu'au S6 ; et pourquoi un encodeur écrit à la main se défend sur ce schéma-là alors qu'il serait une
mauvaise idée sur un schéma qui change souvent.

### Limites connues

**1. Quitter l'application pendant un enregistrement : corrigé, et l'hypothèse qui précédait était
fausse.** Ce paragraphe affirmait d'abord qu'il restait « une fenêtre étroite » où la série pouvait
être perdue. **C'était faux : elle était perdue à chaque fois.** La relecture l'a montré, et le
raisonnement mérite d'être gardé, parce qu'il ne se lit pas dans le code.

Le bouton retour fait exécuter `ON_PAUSE → ON_STOP → ON_DESTROY` dans un **seul message du looper
principal**. Donc : `onScreenStopped()` termine bien la série et appelle `stop()`, qui lance une
coroutine sur `viewModelScope` — `Dispatchers.Main.immediate`, donc son corps démarre tout de suite
— mais ce corps suspend sur `cancelAndJoin()`, le temps que le collecteur se déroule sur un thread
`Default`. Sa reprise est un **autre** message du looper, et avant qu'il ne soit traité,
`onCleared()` a déjà annulé la portée. La coroutine mourait donc avant d'atteindre l'écriture, et
donc avant d'atteindre le `NonCancellable` qui n'entourait que l'écriture. Trente secondes de série
perdues, sans une ligne de journal.

Le correctif est d'étendre `NonCancellable` **au joint aussi**, dans les deux chemins de fin : le
bloc démarre synchronement avant l'annulation, donc il la franchit. Ce qui reste : si le processus
est tué dans l'intervalle, la série est perdue — mais ce n'est plus le cas courant, c'est une mort
de processus.

Leçon à garder : `NonCancellable` protège ce qu'il **entoure**, pas ce qui suit, et une coroutine
lancée sur une portée qu'on est en train de détruire n'a qu'un seul tour de looper d'avance.

**2. Le fichier n'est pas une requête.** `uploadSeries` est `POST /sessions/{sessionId}/series` :
il faut un `sessionId`, qui n'existe pas avant le S6. Ce que SCRUM-37 produit est un **corps** de
requête valide, à valider contre le schéma, pas un appel.

**3. Une seule série par enregistrement, donc `index` vaut 0.** Le rang d'une série dans une séance
n'a pas de sens tant qu'il n'y a pas de séance.

**4. `capture.analysisWidth` et `analysisHeight` portent la taille *redressée*, donc 480x640 en
portrait — ambiguïté du contrat, à faire trancher.** Le nom des champs, leur `example: 640`/`480` et
la décision figée « résolution d'analyse 640x480 » disent tous les trois « la résolution de
l'analyse ». Mais le contrat dit aussi « x et y normalisés par `analysisWidth` et `analysisHeight` »
et « z normalisé par `analysisWidth` », et les coordonnées, elles, sont normalisées par la taille
**redressée** : en portrait, ML Kit reçoit l'image tournée d'un quart de tour, donc les axes sont
échangés et c'est par 480 qu'il faut diviser x.

Les deux lectures ne peuvent pas être vraies à la fois. **La valeur écrite est celle qui permet de
dénormaliser**, parce que c'est à ça que le champ sert dans le texte du contrat : la même série,
même appareil, même configuration, annonce donc 640x480 tenue en paysage et 480x640 tenue en
portrait. Un relecteur du fichier de démo y lira une résolution d'analyse qui change, alors que
c'est le repère des coordonnées qui change.

**Question posée au propriétaire du contrat** — c'est la dernière qui reste ouverte, celle de
`contractVersion` ayant été tranchée depuis : si la réponse
est « ce champ est la résolution d'analyse », alors il faut y écrire 640x480 **et** ajouter au
contrat de quoi dire par quoi les coordonnées sont normalisées — sinon le serveur dénormalisera de
travers une série sur deux. Le code n'est pas changé en attendant : la valeur actuelle est la seule
des deux qui soit exploitable.

### Hors périmètre, assumé

gzip, client HTTP, envoi, file d'attente hors ligne, nouvelles tentatives, sémantique d'idempotence
de `batchId`, choix de l'exercice, rattachement à une séance, base locale — tout le S6. Seuil de
`visibility` (S5), alertes (SCRUM-64), repère de gravité (SCRUM-113), README et démo (SCRUM-38),
injection de dépendances.
