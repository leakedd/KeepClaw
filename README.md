# AndroClaw

**Fork Android de [picoclaw](https://github.com/sipeed/picoclaw) : le gateway + la console web tournent en permanence dans un foreground service, comme un VPN ou Spotify — sans Termux, sans root, sans ADB au quotidien.**

- Nom : AndroClaw
- Base : `sipeed/picoclaw` @ `bbf6893ca7af` (v0.3.2-0.20260819)
- Cible : Android 8.0+ (minSdk 26), testé/compilé pour **Android 16** (compileSdk 36), **arm64-v8a**
- Signature : keystore auto-signé local (`scripts/init-keystore.sh`), installé par `adb install`

---

## 1. Pourquoi une app et pas Termux

Termux exécute le binaire dans le bac à sable d'une app : dès qu'Android gèle ou tue Termux (écran verrouillé, pression mémoire, reboot), **sshd, le CLI et le gateway meurent avec lui**. Aucun `termux-wake-lock` n'y change quoi que ce soit.

AndroClaw passe le **contrat Android** qui autorise un programme à vivre :

| Clause | Implémentation |
|---|---|
| Rester éveillé | `ClawService` en **foreground service** `specialUse` + notification permanente non balayable |
| Survivre au swipe | `android:stopWithTask="false"` + `START_STICKY` + `onTaskRemoved` |
| Repartir au reboot | `BootReceiver` (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`) → `startForegroundService` |
| Ne pas être endormi | exemption Doze via `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (bouton 🔋 dans l'app) |
| Couper à la main | action **« Arrêter »** dans la notification — **visible sur l'écran verrouillé** — et tuile du volet rapide |

Android 15+ interdit les types d'FGS `dataSync`/`mediaProcessing` lancés depuis `BOOT_COMPLETED` ; `specialUse` est le seul qui reste autorisé pour ce cas d'usage (Android 16 sur l'appareil cible).

## 2. Architecture

```
APK AndroClaw (dev.androclaw)
├── app/src/main/jniLibs/arm64-v8a/
│   ├── libpicoclaw.so   ← picoclaw core (agent + gateway)     GOOS=android GOARCH=arm64
│   └── liblauncher.so   ← picoclaw-launcher (console web)     port 18800, 127.0.0.1
├── ClawService     foreground service : spawn le launcher, PICOCLAW_BINARY=libpicoclaw.so
├── MainActivity    WebView sur http://127.0.0.1:18800 + barre ▶ ■ ↻ 🔋
├── ClawTileService tuile du volet rapide (slide = on/off)
├── BootReceiver    relance au démarrage
└── assets/seed/config.json   config d'exemple (modèles + en-têtes opencode-go)
```

### Pourquoi les binaires Go s'appellent `lib*.so`
Depuis Android 10 (targetSdk ≥ 29), le **dataDir d'une app est `noexec`** : impossible d'y poser un exécutable et de le lancer (règle W^X). La **seule** zone exécutable offerte à une app est `nativeLibraryDir` — alimentée par `jniLibs`. D'où :

- nom imposé `lib<qqchose>.so` (sinon PackageManager ne les extrait pas),
- `android:extractNativeLibs="true"` dans le manifeste,
- `packaging.jniLibs.useLegacyPackaging = true` + `noCompress += "so"` dans Gradle.

Résultat : à l'installation, PackageManager dépose les deux binaires dans `/data/app/.../lib/arm64/` (lecture seule, **exécutable**) et `ClawService` les lance avec `setsid` pour que tuer l'arbre de process tue aussi le core spawné par le launcher.

## 3. Build

```bash
# 0) prérequis (macOS)
brew install go gradle
brew install --cask android-commandlinetools
sdkmanager --sdk_root=$HOME/Library/Android/sdk "platform-tools" "platforms;android-36" "build-tools;36.0.0"
npm i -g pnpm@10.33.0          # frontend de la console

# 1) sources
git clone https://github.com/sipeed/picoclaw.git native/picoclaw
cd native/picoclaw && git checkout bbf6893ca7af && cd ../..

# 2) binaires natifs (frontend + core + console → jniLibs)
./scripts/build-native.sh

# 3) APK signée
./scripts/init-keystore.sh        # une seule fois
./scripts/build-apk.sh assembleRelease

# 4) installation
adb install -r app/build/outputs/apk/release/app-release.apk
```

`./gradlew` est épinglé sur **Gradle 8.14.3** + **AGP 8.13.2** (JDK 21). Le projet n'a **aucune dépendance externe** : Java pur + API plateforme (WebView, Service, TileService, Notification) → build rapide, APK légère, surface d'attaque minimale.

## 4. Utilisation

| Action | Comment |
|---|---|
| Démarrer | ouvrir AndroClaw (la WebView charge la console sur `127.0.0.1:18800`) ou tuile du volet rapide |
| Couper | bouton ■ dans l'app · action **Arrêter** de la notification (écran verrouillé compris) · tuile du volet rapide |
| Autoriser la vie en fond | bouton 🔋 une fois (exemption batterie) → sinon Doze coupe le réseau la nuit |
| Ajouter le modèle | console web → Models : base URL `https://opencode.ai/zen/go/v1`, modèle `mimo-v2.5`, clé `sk-…` |
| Si votre passerelle l'exige | Models → *custom headers* : `x-opencode-session` = votre identifiant de session (le seed contient un placeholder, **aucun identifiant réel n'est versionné**) |
| Parler à l'agent | canal **Telegram** (token BotFather) ou tout autre canal activé dans la console |
| Repartir à neuf | bouton **🗑** → confirmation → efface mot de passe, clés, canaux et conversations, puis relance sur la page de première ouverture |

### Réinitialiser (bouton 🗑)

Utile dans deux cas : partager le téléphone, ou **mot de passe de la console oublié**. Le bouton arrête
le service, supprime le dossier de travail `files/picoclaw/` (config, `.security.yml`, sessions,
carnet du gateway, historique, workspace) et les cookies de la WebView, puis relance : le service
ré-sème `assets/seed/config.json` et la console affiche à nouveau la page de création de mot de passe.

Volontairement implémenté **côté app et non via l'API** de la console (`POST /api/config/reset`
exige une session authentifiée) : la réinitialisation doit rester possible quand on ne peut plus
se connecter.

Le fichier de travail est `config.json` dans `/data/data/dev.androclaw/files/picoclaw/` (semé au premier lancement depuis `assets/seed/config.json`). Les clés d'API vivent dans le `.security.yml` du même dossier, **jamais dans l'APK**.

> **Démarrage automatique du gateway (vérifié sur appareil).** Le launcher tente un démarrage du core à chaque lancement : il ne le fait que si le modèle par défaut est *utilisable* — c'est-à-dire si une **clé API existe** pour `mimo` (`gatewayStartReady()` → `apiKey != ""`). Tant qu'aucune clé n'est saisie, la console affiche « Gateway Not Running » et le bouton **Start Gateway** reste nécessaire ; **dès que la clé est enregistrée une fois**, le gateway remonte seul à chaque (re)démarrage du service. Le mode « VPN/Spotify » est donc bien atteignable sans aucune action.
>
> Preuve obtenue hors app (launcher seul + config avec clé, aucune authentification) :
> `Started picoclaw gateway` → `Gateway auto-started` → PID annoncé **vivant** (`kill -0`), console en `302`.

## 5. Vérifications faites sur l'appareil (A57 / Android 16)

| Contrôle | Résultat |
|---|---|
| Extraction des binaires natifs | `liblauncher.so` / `libpicoclaw.so` : `exec=true`, pas de `chmod` nécessaire (PackageManager les extrait exécutables) |
| Enfant du service | `liblauncher.so` a bien pour parent le process `dev.androclaw` → le launcher suit la vie du service |
| Console | `/` → `302` (page de setup à froid) puis `200` ; `/api/auth/status` → `200` |
| Service | `isForeground=true`, `types=0x40000000` (`specialUse`), notification `ONGOING|FOREGROUND`, `category=service`, `vis=PUBLIC`, 1 action |
| UI | WebView rend la console, sélecteur de modèle sur `mimo` (seed pris en compte) |
| Batterie | `Added: dev.androclaw` (exemption Doze posée via `adb`) |
| Relance après mise à jour | `MY_PACKAGE_REPLACED` → le service repart et relance le launcher |

## 6. Limites assumées

- **arm64-v8a uniquement** (téléphone moderne) ; ajouter `x86_64`/`armeabi-v7a` = recompiler et les déposer dans `jniLibs`.
- Pas de root, Knox intact : l'app reste dans son bac à sable ; elle ne lit que ses propres fichiers et `/sdcard` avec permission.
- La console et l'API sont liées à **127.0.0.1** : rien n'est exposé sur le réseau local.
- `specialUse` est accepté en sideload ; sur le Play Store il faudrait justifier le type (pas notre cas).
- Le frontend React de la console est embarqué à la compilation : modifier l'UI impose de rebâtir `liblauncher.so`.
