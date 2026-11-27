GlassHome 5.2 source update
===========================

Version: 5.2 (versionCode 7)
Package: com.clean.glasshome

What changed
------------
- Reworked the home screen around a full-bleed warm wallpaper, top-right quick controls,
  a featured app panel, and one horizontal app dock, following the supplied reference.
- Added short, event-driven focus, dock-scroll, wallpaper-fade, and app-launch transitions.
  No continuous animation loop or per-frame blur is used.
- Settings opens the projector's general Android Settings screen.
- Apps opens Android's application-management screen and uses a separate grid icon.
- Wallpaper opens the image picker, copies the chosen image into GlassHome's private storage,
  and restores it on the next launch. This avoids depending on a temporary document-provider URI.
- The built-in wallpaper now uses a warm blurred amber/red background.
- No system APKs are removed and the factory launcher remains untouched.

Remote navigation
-----------------
- LEFT / RIGHT: move through the app dock or quick controls.
- UP: move from the dock to the featured app, then to the top controls.
- DOWN: move back toward the dock.
- OK / ENTER: open the focused app or quick control.

Important for updating your existing project
---------------------------------------------
- Extract this archive OVER your existing ~/GlassHome5 folder. Do not delete the project first.
- This archive intentionally does not contain app/debug.keystore or .git.
  Keep your existing app/debug.keystore in place so the release APK uses the same signing key.
- The build workflow is the existing GitHub Actions workflow. Push the updated source, wait for
  the Build APK workflow to succeed, then download the glasshome-release artifact.
- Install with adb install -r so Android attempts an in-place update.
