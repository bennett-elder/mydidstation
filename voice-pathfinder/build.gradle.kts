// Versions are pinned to android/build.gradle.kts on purpose. The pathfinder is a
// throwaway probe for notes/VOICE-CALL-PLAN.md Phase 0, but it has to compile against
// the same toolchain as the app it is answering questions for, or a finding from here
// says nothing about what the real app can do.
plugins {
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}
