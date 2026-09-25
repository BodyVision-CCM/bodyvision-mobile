# BodyVision — Spike SCRUM-22 : PoC ML Kit Pose Detection

## Contexte

BodyVision est un projet académique de M2 (Master Cloud Computing and Mobility) : une
application Android qui analyse la biomécanique d'un mouvement de musculation en temps réel
sur le téléphone (Edge), puis envoie les séances vers une plateforme Cloud (GKE, RabbitMQ,
PostgreSQL) pour l'historique et la progression.

Ce dépôt-ci ne contient QUE le spike : un proof of concept jetable dont le seul but est de
**mesurer** si ML Kit Pose Detection tient 30 FPS sur un vrai téléphone sans surchauffe.
Ce n'est pas l'application finale. On ne cherche ni une belle architecture, ni des tests,
ni du Clean Code : on cherche des chiffres fiables.

Le livrable du ticket est **une note de mesures**, pas une application.

## Appareil de test (unique cible)

- Honor 400, modèle DNY-NX9
- Qualcomm Snapdragon 7 Gen 3
- Android 16
- Les tests se font TOUJOURS sur cet appareil physique, jamais sur l'émulateur (pas de vraie
  caméra, pas le bon GPU, pas de gestion thermique).

## Objectif du spike

Répondre à trois questions, chiffres à l'appui :

1. Tient-on 30 FPS d'analyse sur 10 minutes en continu ?
2. Le téléphone chauffe-t-il au point de se brider ?
3. Les angles calculés sont-ils assez stables pour déclencher une alerte posturale fiable ?

### Seuils de décision (fixés AVANT de mesurer)

- FPS >= 24 soutenu à 10 minutes
- Latence d'inférence p95 < 40 ms
- État thermique au maximum `THERMAL_STATUS_LIGHT`
- Écart-type de l'angle du genou sur posture figée < 3°

Si ces seuils ne sont pas atteints, les replis à documenter sont, dans l'ordre : réduire la
résolution d'analyse, brider l'analyse à 15 FPS en gardant la preview à 30, puis revoir
l'exigence de 30 FPS du cahier des charges.

## Stack imposée

- Kotlin, **vues XML** (pas Compose : `PreviewView` de CameraX est une vue classique et
  l'overlay squelette est un `Canvas` custom, c'est plus direct pour du code jetable)
- CameraX : `Preview` + `ImageAnalysis`
- ML Kit Pose Detection, les deux SDK :
  ```kotlin
  implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
  implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")
  ```
- minSdk 24, targetSdk 36, compileSdk 36

### Deux règles non négociables

1. La stratégie de backpressure de `ImageAnalysis` **doit rester à sa valeur par défaut**
   `STRATEGY_KEEP_ONLY_LATEST`. Sinon les frames s'accumulent et la latence mesurée est
   fausse.
2. ML Kit choisit son accélération matérielle de façon non bloquante : les premières
   exécutions tournent sur CPU avant que la meilleure configuration soit retenue.
   **Les 30 premières secondes de chaque run doivent être exclues des statistiques.**
   L'application doit le faire toute seule (période de chauffe explicite, visible à l'écran).

## Ce que l'application doit faire

### Écran unique

- Preview caméra plein écran (caméra arrière)
- Overlay du squelette dessiné par-dessus (les 33 landmarks reliés)
- HUD permanent en haut, lisible à 2-3 m de distance :
  - FPS instantané (moyenne glissante sur 1 s) et FPS moyen du run
  - latence d'inférence : médiane et p95, en ms
  - température batterie en °C
  - état thermique (NONE / LIGHT / MODERATE / SEVERE / CRITICAL)
  - niveau de batterie en %
  - temps écoulé depuis le début du run
  - angle du genou droit, en degrés
- Un sélecteur de configuration (spinner ou boutons) pour basculer entre les 4 configs
  SANS recompiler
- Un bouton Démarrer / Arrêter le run

### Les 4 configurations à pouvoir sélectionner

| # | Modèle | Accélération | Résolution d'analyse |
|---|--------|--------------|----------------------|
| 1 | base (`PoseDetectorOptions`) | défaut (ML Kit choisit) | 1280x720 |
| 2 | base | `CPU_GPU` | 1280x720 |
| 3 | base | la meilleure des deux précédentes | 640x480 |
| 4 | accurate (`AccuratePoseDetectorOptions`) | la meilleure | 1280x720 |

Toutes en `STREAM_MODE`.

### Instrumentation (le cœur du ticket)

L'utilisateur ne doit PAS avoir à lancer de commandes adb. L'application relève tout
elle-même :

- **FPS** : compter les callbacks `addOnSuccessListener`, pas les frames caméra. C'est le
  débit d'analyse qui compte.
- **Latence** : chronométrer avec `SystemClock.elapsedRealtimeNanos()` autour de
  `poseDetector.process(image)`, conserver médiane et p95 sur une fenêtre glissante.
- **Frames droppées** : compter les frames caméra reçues moins les frames analysées.
- **Température batterie** : `BatteryManager` / `Intent.ACTION_BATTERY_CHANGED`, extra
  `EXTRA_TEMPERATURE` (valeur en dixièmes de °C : 320 = 32 °C).
- **État thermique** : `PowerManager.getCurrentThermalStatus()`, et enregistrer aussi les
  changements via `addThermalStatusListener`.
- **Niveau de batterie** : au début et à la fin du run.
- **Précision** : moyenne de `inFrameLikelihood` sur épaule, hanche, genou et cheville
  (côté droit).
- **Stabilité d'angle** : angle du genou droit (hanche-genou-cheville, via `atan2`),
  avec écart-type glissant sur 10 secondes.

### Export CSV

À l'arrêt du run, écrire un fichier CSV dans le dossier public **Documents** du téléphone
(via `MediaStore`, pour qu'il soit visible depuis un explorateur de fichiers et par USB
sans adb), nommé :

```
bodyvision_spike_<config>_<horodatage>.csv
```

Deux blocs dans le fichier :

1. **Une ligne d'en-tête de run** : modèle de téléphone, version d'Android, configuration,
   résolution, date, durée, batterie début/fin.
2. **Une ligne par seconde écoulée** avec les colonnes :
   `t_secondes, fps, latence_mediane_ms, latence_p95_ms, frames_droppees, temp_batterie_c,
   etat_thermique, batterie_pct, likelihood_moyen, angle_genou_deg, ecart_type_angle_deg`

Ajouter aussi un **résumé de fin de run affiché à l'écran** (dialog non dismissible tant
qu'on n'a pas appuyé sur OK) avec : FPS moyen et minimum, latence médiane et p95,
température de début et de fin, état thermique maximal atteint, écart-type d'angle moyen,
et un verdict automatique SEUILS OK / SEUILS NON ATTEINTS calculé à partir des seuils
ci-dessus. C'est cet écran que l'utilisateur recopiera dans sa note de mesures.

### Bonus utile (ticket SCRUM-23)

Ajouter un bouton qui sérialise **une frame complète en JSON** (les 33 landmarks avec x, y,
z et `inFrameLikelihood`) et l'écrit à côté du CSV, puis affiche à l'écran le poids en
octets de cette frame, et une estimation du poids d'une série de 30 secondes à 30 FPS,
brute et compressée en gzip. Ces chiffres servent à dimensionner le contrat de télémétrie
du projet.

## Protocole de test (à rappeler à l'utilisateur dans l'app)

Afficher un court rappel au lancement :

- Téléphone sur trépied, sujet en pied, à 2-3 m
- Luminosité d'écran fixée à 50 %
- **Téléphone débranché** (la charge fausse toute mesure thermique)
- Run de 10 minutes, mouvement continu (squats lents), pas de sujet immobile
- 15 minutes de refroidissement entre deux runs, départ sous 30 °C
- 3 runs par configuration

## Conventions de travail

- Branche : `spike/mlkit-pose`
- Commits en français, format court : `feat: ...`, `fix: ...`, `docs: ...`
- Une PR vers `main`, merge en squash, 1 relecture obligatoire (règle d'équipe)
- Ne pas ajouter de dépendances au-delà de CameraX et ML Kit
- Ne pas mettre en place de tests unitaires : c'est un spike jetable, c'est assumé

## Ce qu'il ne faut PAS faire

- Pas d'émulateur
- Pas d'architecture en couches, pas de Hilt, pas de Room, pas de Retrofit
- Pas d'envoi réseau : le spike est 100 % local
- Pas de Compose
- Ne pas masquer les mauvais chiffres : si les 30 FPS ne tiennent pas, le spike a réussi
  quand même. Un résultat négatif documenté vaut mieux qu'un résultat flatteur.
