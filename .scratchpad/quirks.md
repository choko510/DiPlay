# Quirks

- Android SDK variables and `local.properties` are absent. Android Gradle tasks stop during `:shared` configuration with “SDK location not found”; isolated pure Kotlin tests can still be compiled with the Gradle-distributed Kotlin compiler and JUnit.
- The 8-second wired AirPlay startup timeout still has no measured slow-device distribution; validate or adjust it with real-device connection timing before treating it as a stable threshold.
