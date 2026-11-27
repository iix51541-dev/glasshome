GlassHome 5.3 (versionCode 8) - premium redesign
================================================
Package / applicationId / signing config: unchanged (com.clean.glasshome).

Changed files
  app/src/main/java/com/clean/glasshome/HomeView.kt   rewritten (all drawing, animation, navigation)
  app/src/main/java/com/clean/glasshome/Catalog.kt    tile pre-rendering + cache + native-Settings shortcut
  app/src/main/java/com/clean/glasshome/Model.kt      new glyph ids, Item.art
  app/src/main/java/com/clean/glasshome/MainActivity.kt  lifecycle, media monitor, safe reloads
  app/src/main/java/com/clean/glasshome/Media.kt      NEW: media-session monitor + listener anchor
  app/src/main/AndroidManifest.xml                    + MediaListenerService
  app/build.gradle.kts                                versionCode 8 / versionName 5.3 only

Removed on request: Wallpaper button (+ picker), bottom-left remote hints.
Kept: built-in wallpaper, a wallpaper chosen earlier (still restored), Store/Wi-Fi/Settings
shortcuts, app discovery, recents ordering, weather, remote navigation.

Remote: LEFT/RIGHT browse, UP/DOWN move between top controls / media panel / dock, OK opens, BACK returns to dock.
