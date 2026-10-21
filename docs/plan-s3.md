# Sprint S3 — fonder l'application BodyVision (SCRUM-30 → 31 → 32)

## Contexte

`main` est vierge : aucun projet Android, seulement `CLAUDE.md`, `README.md` et
`.claude/agents/`. Tout le code qui marche vit sur `origin/spike/mlkit-pose` (déjà
récupérée localement), sous la forme d'une *god activity* de 569 lignes mêlant la boucle
ML Kit, l'interface et l'instrumentation de mesure du ticket SCRUM-22 (CSV, thermique,
batterie, HUD, garde-fou de température, protocole de run).

L'objectif du sprint est de **promouvoir** ce code : garder les parties mesurées et
validées (boucle d'analyse, configuration CameraX, mathématique des angles, configuration
Gradle), jeter l'instrumentation devenue inutile, et le ranger en couches testables sans
appareil. Résultat attendu : une application Compose qui affiche l'aperçu caméra et les
33 landmarks en flux live, avec un domaine pur couvert par des tests JVM.

## Ce que j'ai trouvé dans le spike et qui change le plan

Quatre écarts entre ta description et le code réel. Rien de bloquant, mais ça déplace du
travail.

1. **`PoseGeometry.kt` n'est pas du Kotlin pur.** Il ne dépend pas de l'interface, c'est
   vrai, mais il importe `com.google.mlkit.vision.pose.Pose` et `PoseLandmark` :
   `rightKneeAngleDegrees(pose: Pose)`. Or `CLAUDE.md` exige que le domaine ne connaisse
   pas ML Kit. Je garde **la mathématique à l'identique** (`atan2`, repli à `360 - x`
   au-delà de 180°) et je change uniquement la signature pour qu'elle prenne des types du
   domaine. C'est ce qui le rend testable sans appareil.
2. **`PoseConfig.kt` est de l'instrumentation**, pas de la configuration : c'est l'énumération
   des 4 configurations à comparer, avec un sélecteur d'accélération exposé dans
   l'interface « pour tester les deux sans recompiler ». La décision est figée depuis
   (base / `CPU_GPU` / 640x480 / `STREAM_MODE`). Il n'en reste qu'une constante, et comme
   elle porte `android.util.Size` et les options ML Kit, elle va dans la couche données,
   pas dans le domaine. La dépendance `pose-detection-accurate` disparaît avec.
3. **Le thème du spike est un thème Views.** Vérifié : `values/themes.xml` et
   `values-night/themes.xml` héritent de `Theme.Material3.DayNight.NoActionBar`, qui vient
   de `com.google.android.material`. Retirer cette dépendance sans toucher au thème casse
   le lien des ressources (`resource style/Theme.Material3… not found`). Traité
   explicitement dans SCRUM-30 ci-dessous.
4. **`POSE_CONNECTIONS` et `JsonFrameExporter` ne sont pas repris ce sprint.** Pas
   d'overlay et pas de télémétrie avant S4/S6 : les importer maintenant, c'est du code
   mort. Ils restent sur `spike/mlkit-pose`, qu'on ne supprime pas. En revanche je reprends
   dès maintenant **la normalisation des coordonnées** (0..1, axes échangés si rotation
   90/270) : c'est exigé par `CLAUDE.md`, c'est le format de télémétrie de S6, et ça fait
   de la projection de l'overlay S4 une simple multiplication par la taille de la vue.

### Décisions qui viennent de toi

Package `fr.upjv.bodyvision`, compteur de FPS à l'écran, **rotation déverrouillée**. Je
n'étais pas d'accord sur le dernier point et tu as tranché après avoir vu les deux coûts :
le plan assume donc le travail paysage dans S3 (section SCRUM-31) et le S4 devra projeter
l'overlay dans les deux orientations.

### Décisions qui viennent de moi

L'écran live est rangé dans `session/` dès SCRUM-31 (son rôle est de piloter une session de
captation, c'est le nom de `CLAUDE.md`, et ça évite un renommage en S4). Pas de package
`session/domain` ni d'interface d'abstraction tant qu'il n'y a qu'une implémentation —
KISS avant inversion de dépendance.

---

## SCRUM-30 — Initialiser le projet

Branche `feat/scrum-30-initialisation-projet`.

### Premier pas, avant tout code

Créer la branche depuis `main`, y écrire ce plan dans `docs/plan-s3.md` et le committer
(`docs: plan du sprint S3`). Le plan validé vit dans le dépôt, pas seulement dans cette
conversation — c'est aussi ce que je pourrai montrer au jury comme trace de conception.

### Reprise de la configuration Gradle

Depuis le spike, en adaptant : `gradlew`, `gradlew.bat`, `gradle/wrapper/*` (Gradle 9.1.0,
récupérés tels quels via `git checkout origin/spike/mlkit-pose -- …`), `gradle.properties`,
`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`, les `mipmap-*`, `drawable/ic_launcher_*`.

Le spike est en **AGP 9.0.1, qui embarque son propre support Kotlin** : `app/build.gradle.kts`
n'applique aucun plugin `kotlin-android`, et l'appliquer entrerait en conflit. Trois
conséquences :

- Compose s'active avec le plugin `org.jetbrains.kotlin.plugin.compose` (version alignée
  sur le KGP embarqué par AGP, `2.2.10` d'après la matrice de versions JetBrains) +
  `buildFeatures { compose = true }`. **C'est le point à valider en premier à la
  compilation** : si la version ne correspond pas, Gradle le dit explicitement et je te
  donne le message exact avant de corriger.
- Pour ktlint, il faut le plugin `org.jlleitschuh.gradle.ktlint` **14.2.0** : le support du
  Kotlin intégré d'AGP 9 n'existe qu'à partir de la 13.1.0.
- `compileSdk { version = release(36) }` est la nouvelle DSL AGP 9 : on la garde telle quelle.

Ajouts de dépendances : `compose-bom` (`2026.08.00`, à confirmer à la compilation),
`ui`, `ui-graphics`, `ui-tooling-preview`, `material3`, `activity-compose`,
`lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, `ui-tooling` en `debugImplementation`.
Retraits : `appcompat`, `material` (Views), `constraintlayout`, `pose-detection-accurate`,
`espresso`, les deux tests d'exemple, `activity_main.xml`, la permission
`WRITE_EXTERNAL_STORAGE`.

### Thème et manifeste — explicitement

C'est le point le plus facile à rater, parce que l'erreur qu'il produit (`resource
style/Theme.Material3.DayNight.NoActionBar not found`) a un correctif réflexe qui est
mauvais : remettre `com.google.android.material`, et ramener toute la pile Views en douce.

Le thème XML d'une application Compose n'a qu'un rôle : habiller la fenêtre **avant** que
Compose ne dessine quoi que ce soit. Il n'a donc pas besoin de Material Components. Il
devient un thème du framework, sans aucune dépendance :

- `res/values/themes.xml` : un seul style, `Theme.BodyVision`, parent
  `android:Theme.Material.NoActionBar`, avec `android:windowBackground` en noir — un écran
  caméra qui démarre sur un flash blanc, ça se voit.
- `res/values-night/themes.xml` : **supprimé**. Le dédoublement clair/sombre appartenait au
  thème Views ; le thème réel vit dans `core/ui/theme/Theme.kt` et l'application est sombre
  par nature.
- `res/values/colors.xml` : **supprimé** (il ne contient que `black` et `white`). Les
  couleurs Compose vont dans `core/ui/theme/Color.kt`.
- Manifeste : `android:theme="@style/Theme.BodyVision"` sur `<application>`, l'attribut
  dupliqué sur `<activity>` retiré, et `android:screenOrientation="portrait"` retiré
  (rotation déverrouillée).
- `MainActivity : ComponentActivity` (plus `AppCompatActivity`), avec `enableEdgeToEdge()` :
  en `targetSdk 36` le bord-à-bord est imposé, et sans lui la ligne de compteurs passerait
  sous la barre d'état.

**Règle que je me donne** : si une ressource de thème manque à la compilation, le correctif
est de pointer le parent vers un thème du framework (`android:Theme.*`), jamais de
réintroduire `appcompat` ou `com.google.android.material`.

### Arborescence créée

```
app/src/main/java/fr/upjv/bodyvision/
  MainActivity.kt                    ComponentActivity + enableEdgeToEdge + setContent, rien d'autre
  core/ui/theme/{Theme,Color,Type}.kt
  pose/domain/
    PoseLandmarkType.kt              enum des 33 points, zéro dépendance
    NormalizedLandmark.kt            data class(x, y, z, visibility) en 0..1
    PoseFrame.kt                     data class(landmarks: Map<…>, timestampMs)
    PoseGeometry.kt                  angleDegrees(frame, a, b, c): Double?  (atan2 du spike)
app/src/test/java/fr/upjv/bodyvision/pose/domain/PoseGeometryTest.kt
```

Un landmark manquant = **clé absente** de la map, jamais une valeur nulle : c'est ce qui
rend le `!!` structurellement impossible côté appelant.

### Qualité

- `android { lint { abortOnError = true } }`.
- **ktlint** plutôt que detekt : ktlint est un *formateur* (style Kotlin officiel,
  correction automatique, aucune règle à écrire), alors que detekt est un analyseur statique
  dont le lint Android couvre déjà le périmètre Android — KISS, une seule chose à maîtriser.
  `.editorconfig` avec `ktlint_code_style = ktlint_official` et `max_line_length = 120`.
  Le plugin accroche `ktlintCheck` sur `check`, donc `./gradlew build` échoue sur un
  fichier mal formaté, sans commande supplémentaire à retenir.
- `PoseGeometryTest` : angle droit à 90°, points alignés à 180°, segment replié à 0°,
  landmark absent → `null`, deux points confondus (`atan2(0, 0)`, le cas qui ferait une
  division par zéro avec un produit scalaire), angle rentrant > 180° ramené dans [0, 180],
  et symétrie `angle(a,b,c) == angle(c,b,a)`. Un test = un comportement, nommé par la raison
  de son échec.

**Fini quand** `.\gradlew.bat clean build test lint` passe et qu'aucune logique ne vit dans
`MainActivity`.

### Ce que je t'explique à ce ticket
`data class` (et pourquoi `equals`/`copy` gratuits changent la façon d'écrire des tests),
`enum class` avec propriétés, nullabilité Kotlin et `?:` contre le `null` de PHP/C#,
fonctions de premier niveau sans classe enveloppante, et pourquoi un domaine sans Android
se teste sur la JVM en quelques millisecondes.

---

## SCRUM-31 — CameraX et permissions

Branche `feat/scrum-31-camerax-permissions`.

```
camera/data/CameraSessionBinder.kt      bind(lifecycleOwner, previewView, analyzer?) / unbind()
camera/ui/CameraPreview.kt              AndroidView { PreviewView } + DisposableEffect
session/ui/LiveSessionScreen.kt         affiche un état, émet des actions
session/ui/LiveSessionViewModel.kt      StateFlow<LiveSessionUiState> + Channel d'événements
session/ui/LiveSessionUiState.kt        data class d'état, scalaires uniquement
session/ui/CameraPermissionStatus.kt    enum: UNKNOWN, GRANTED, DENIED, PERMANENTLY_DENIED
```

- La permission est demandée **au moment où l'aperçu est demandé**, pas dans `onCreate`,
  via `rememberLauncherForActivityResult(RequestPermission())`.
- Les trois cas : accordée → liaison des cas d'usage ; refusée (`shouldShowRequestPermissionRationale`
  vrai) → écran d'explication avec un bouton « Réessayer » ; refusée définitivement → bouton
  qui ouvre `ACTION_APPLICATION_DETAILS_SETTINGS`. **Le piège** : avant la première demande,
  `shouldShowRequestPermissionRationale` est aussi faux, donc « jamais demandé » et « refusé
  définitivement » sont indistinguables. Le drapeau `hasBeenAskedOnce` vit dans le
  `SavedStateHandle` du ViewModel : il survit à la rotation **et** à la mort du processus.
- `CameraSessionBinder` reprend du spike : `ProcessCameraProvider`, `unbindAll()` avant
  chaque liaison, `CameraSelector.DEFAULT_BACK_CAMERA`, `Preview` + `ImageAnalysis` liés au
  cycle de vie, `ResolutionSelector` à 640x480 avec `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`,
  `STRATEGY_KEEP_ONLY_LATEST` (décision figée, on n'y touche pas).
- Aucun `Context` ni `LifecycleOwner` dans le ViewModel : c'est la fuite classique.
  `bindToLifecycle` reçoit le `LocalLifecycleOwner` depuis le composable, et CameraX libère
  donc la caméra tout seul en arrière-plan.
- Un message ponctuel (« caméra indisponible ») part dans un `Channel` consommé une fois
  par un `LaunchedEffect`, pas dans un champ d'état qui se réafficherait après rotation.

### Ce que la rotation déverrouillée coûte ici

- **Mise en page tenant dans les deux sens** : la ligne de compteurs est *superposée* à
  l'aperçu dans un `Box`, pas empilée au-dessus dans une `Column` — une superposition n'a
  pas de hauteur à répartir, donc elle est indifférente à l'orientation. Les écrans de
  permission sont un contenu centré à largeur maximale bornée, dans un conteneur
  défilable : correct en paysage où la hauteur disponible est faible. Insets gérés par
  `WindowInsets.safeDrawing` (encoche à gauche en paysage).
- **`targetRotation` sur `ImageAnalysis`** : `Preview` n'en a pas besoin, `PreviewView`
  s'aligne seule sur l'affichage. `ImageAnalysis` prend par défaut la rotation de
  l'affichage au moment de la liaison, et comme une rotation portrait↔paysage recrée
  l'activité et relie tout à neuf, c'est juste… sauf pour le demi-tour. Passer de paysage à
  paysage inversé **ne change pas la configuration**, donc pas de recréation, pas de
  nouvelle liaison : `imageInfo.rotationDegrees` reste périmé de 180° et les landmarks
  arrivent à l'envers, sans aucun message d'erreur. En S3 rien ne les dessine, donc c'est
  invisible ; en S4 l'overlay serait faux et on chercherait ailleurs. Je mets donc un
  `OrientationEventListener` dans `CameraSessionBinder`, qui met `imageAnalysis.targetRotation`
  à jour (~12 lignes, désinscrit dans `unbind()`).

**Fini quand** l'aperçu s'affiche, survit à une rotation et à un aller-retour en
arrière-plan, et que le logcat ne signale aucune fuite. **Tu testes sur le Honor 400, je
m'arrête ici.**

### Ce que je t'explique à ce ticket
Cycle de vie Android et pourquoi un `ViewModel` survit à la rotation alors que l'activité
est détruite ; recomposition ; `remember` contre `rememberSaveable` contre ViewModel ;
`AndroidView` comme pont vers l'ancien monde des Views ; `DisposableEffect` ; `StateFlow`
contre `LiveData` ; `let` / `apply` / `also` sur les cas réels du fichier ; `sealed class`
contre `enum` pour un état.

---

## SCRUM-32 — Détection de pose en flux live

Branche `feat/scrum-32-detection-pose-live`.

```
pose/data/MlKitPoseDetectorConfig.kt   l'unique configuration figée (base, CPU_GPU, STREAM_MODE, 640x480)
pose/data/PoseLandmarkMapper.kt        Int ML Kit → PoseLandmarkType + normalisation 0..1
pose/data/MlKitPoseSource.kt           ImageAnalysis.Analyzer + SharedFlow<PoseFrame>, possède le détecteur
app/src/test/.../pose/data/PoseLandmarkMapperTest.kt
```

- `MlKitPoseSource` reprend **tel quel** le cœur de l'analyseur du spike, qui est la partie
  mesurée : le portillon `AtomicBoolean` (une frame arrivant pendant une inférence est
  abandonnée, l'analyse suivante n'est jamais bloquée), le calcul des dimensions
  `imgW`/`imgH` échangées si la rotation vaut 90 ou 270, `InputImage.fromMediaImage`, et
  surtout **`imageProxy.close()` sur les trois chemins** : sortie anticipée, succès, échec
  (`addOnCompleteListener`). L'annotation `@ExperimentalGetImage` suit `imageProxy.image`.
- **Un écart assumé avec le spike**, à valider par toi : les écouteurs de `Task` ML Kit
  s'exécutent par défaut sur le thread principal, donc le spike y convertissait et y
  dessinait. Je passe l'exécuteur d'analyse à `addOnSuccessListener(executor, …)` et
  `addOnCompleteListener(executor, …)` pour que la conversion des 33 landmarks, 30 fois par
  seconde, ne tombe pas sur le thread principal. Ça ne touche à aucune des décisions figées
  (modèle, accélération, résolution, mode, backpressure) et ça ne peut pas baisser le débit
  d'inférence, mais ça change les conditions de la mesure de SCRUM-22 : dis-le si tu
  préfères rester strictement à l'identique.
- **Chaîne de possession, explicitement** : le `LiveSessionViewModel` possède le
  `MlKitPoseSource`, qui possède le détecteur ML Kit **et l'exécuteur d'analyse**, et
  `onCleared()` ferme toute la chaîne. Rien de tout ça n'est créé dans le composable, même
  avec `remember` : un `remember` ne survit pas à un changement de configuration, donc
  chaque rotation recréerait le détecteur et redéclencherait les 30 secondes de chauffe
  ML Kit — et la rotation est maintenant déverrouillée, donc le cas est banal. L'exécuteur
  suit le détecteur et non le composable, parce que les écouteurs ML Kit tournent dessus :
  deux durées de vie différentes, et un écouteur se retrouverait sans exécuteur.
- L'arrêt est `shutdown()`, **jamais `shutdownNow()`** : `imageProxy.close()` vit dans
  `addOnCompleteListener`, qui s'exécute désormais sur cet exécuteur. Avec `shutdownNow()`,
  une inférence en vol au moment du démontage verrait son écouteur rejeté, et le `close()`
  n'aurait jamais lieu — le gel de caméra sans message, exactement le défaut que
  `CLAUDE.md` met en tête de liste.
- Frontière : ML Kit entre, `PoseFrame` sort. Aucun type `com.google.mlkit` au-delà de
  `pose/data`.

### Deux flux séparés, pas un état qui transporte les 33 landmarks

Faire du payload un champ de `LiveSessionUiState` publierait un nouvel objet d'état 30 fois
par seconde. Le coût qui compte n'est pas l'allocation de la map — ML Kit alloue déjà ses 33
objets par frame de toute façon — c'est que **chaque publication recompose la ligne de
compteurs**, soit 30 recompositions par seconde pour un texte qu'un œil humain lit une fois
par seconde. Et la dégradation progressive qui en résulte ressemble exactement au symptôme
d'un `ImageProxy` non fermé, c'est-à-dire qu'on irait chercher le défaut au mauvais endroit.

- **Compteurs** → `LiveSessionUiState`, scalaires uniquement : `permission`,
  `hasBeenAskedOnce`, `fps`, `detectedLandmarkCount`. L'état est construit par
  `combine(permission, battement).stateIn(viewModelScope, WhileSubscribed(5_000), initial)` :
  une publication par seconde, et le battement **s'arrête quand plus personne ne collecte**
  — pas de scrutation en arrière-plan.
- **Le compteur est incrémenté dans `MlKitPoseSource`**, à la fin de l'inférence et **avant
  l'émission**, au même endroit que `onFrameAnalyzed` du spike : les deux chiffres restent
  donc comparables aux 29,4 FPS de SCRUM-22. Compter après le flux mesurerait ce qui survit
  au buffer `DROP_OLDEST`, pas le débit de l'analyse — on afficherait 26 puis 23 sous charge
  thermique pour une inférence à 29, on passerait sous le critère des 24 FPS et on
  chercherait une régression qui n'existe pas. Et en S3 ce serait même zéro, puisque aucun
  composable ne collecte le payload.
  - `AtomicInteger` consommé par `consumeAnalyzedFrameCount()` (`getAndSet(0)`, le nom dit
    la remise à zéro), plus un `@Volatile var lastLandmarkCount` écrit au même endroit : le
    ViewModel lit les deux à chaque battement et ne collecte rien.
  - **Remise à zéro à la reprise de souscription** : `onStart { source.consumeAnalyzedFrameCount() }`
    sur le flux de battement, sinon le premier battement après un retour d'arrière-plan
    publierait d'un coup les frames accumulées pendant la fenêtre de grâce de 5 s.
- **Payload** → `MutableSharedFlow<PoseFrame>(replay = 1, extraBufferCapacity = 0,
  onBufferOverflow = DROP_OLDEST)`, exposé en `SharedFlow`. Le ticket dit « exposition via
  un `StateFlow` » ; je m'en écarte sur un point et voilà pourquoi : un `StateFlow` appelle
  `equals()` à chaque affectation pour décider s'il publie, soit une comparaison de map de
  33 entrées 30 fois par seconde, pour un résultat toujours `false`. Un `SharedFlow` à
  `replay = 1` donne la même garantie utile (le dernier frame est disponible pour un
  collecteur qui arrive en retard, ce dont l'overlay S4 a besoin après une recomposition)
  sans la comparaison. C'est une ligne à changer si tu préfères la lettre du ticket.

En S3 aucun composable ne collecte le payload : la preuve que les landmarks sont bien
disponibles, c'est le compteur. Le flux existe parce que SCRUM-32 le demande et que l'overlay
S4 s'y branche sans rien modifier.

### Tests
Sur la fonction **pure** de normalisation (pixels + dimensions → 0..1, valeurs bornées,
axes échangés à 90/270). Le reste du mappeur demande des types ML Kit, donc un appareil :
pas de test qui simulerait la logique qu'il prétend tester.

**Fini quand** la détection tourne en continu à ≥ 24 FPS sur le Honor 400, sans gel ni
fuite, et que le compteur de landmarks bouge à l'écran.

### Ce que je t'explique à ce ticket
Coroutines et `Flow` (froid) contre `StateFlow`/`SharedFlow` (chaud), et pourquoi le choix
entre les deux est une question de coût de comparaison ; `WhileSubscribed` ; pourquoi
`ImageProxy.close()` est obligatoire et ce qui se passe exactement quand il manque ;
`collectAsStateWithLifecycle` ; fonctions d'extension pour les conversions de frontière ;
`@Volatile` et `AtomicBoolean` quand deux threads se croisent ; pourquoi on jette une frame
plutôt que de la mettre en file d'attente.

---

## Méthode de travail

1. Un ticket à la fois, dans l'ordre. Je m'arrête à la fin de chacun, tu compiles et tu
   testes sur le Honor 400 — toi seul peux valider sur appareil réel.
2. Avant chaque pull request : relecture du diff par le sous-agent `revue-bodyvision`.
3. Commits en français, format court (`feat:`, `test:`, `docs:`), petits et atomiques.
   Aucun commit ni push sans que tu le demandes. `main` est protégée : PR en squash.

## Vérification

À chaque fin de ticket, dans cet ordre :

```powershell
.\gradlew.bat clean build test lint     # compilation, tests JVM, lint Android, ktlint
.\gradlew.bat installDebug              # déploiement sur le Honor 400 branché
```

Je te donne la sortie réelle, échec compris. Puis, sur l'appareil :

- **SCRUM-30** : l'application se lance sur un écran vide thématisé, fond noir, sans barre
  d'action. Rien d'autre à tester : tout le comportement est couvert par `./gradlew test`.
- **SCRUM-31** : refuser la permission une fois → écran d'explication, « Réessayer » relance
  la demande ; refuser deux fois → bouton vers les réglages, et revenir avec la permission
  accordée affiche l'aperçu sans redémarrer l'application ; tourner le téléphone en paysage
  et revenir ; **puis tourner de paysage à paysage inversé** (c'est le cas sans changement
  de configuration, celui que `targetRotation` traite) ; passer en arrière-plan et revenir.
  Surveiller le logcat (`CameraX`, `Leak`).
- **SCRUM-32** : le compteur de FPS reste ≥ 24 et le nombre de landmarks oscille autour de
  33 quand tu es dans le cadre, 0 quand tu sors. Laisser tourner 2-3 minutes : ni gel, ni
  chute progressive du FPS (là, et seulement là, le suspect est un `ImageProxy` non fermé).
  Les 30 premières secondes sont la chauffe ML Kit et ne comptent pas.

## Hors périmètre, assumé

Overlay squelette, export JSON de télémétrie, couche réseau, base locale, injection de
dépendances, `session/domain`. Rien de tout ça n'a de consommateur avant S4 ou S6.
