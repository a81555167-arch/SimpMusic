import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.aboutlibraries.multiplatform) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.sentry.gradle) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.build.config) apply false
    alias(libs.plugins.osdetector) apply false
    alias(libs.plugins.conveyor) apply false
    alias(libs.plugins.compose.hotReload) apply false
}


// The core submodule is upstream-owned, so keep the small audio-only compatibility patch here
// instead of pretending the submodule itself was forked. Legacy queues can retain "Video"-prefixed
// media IDs; Media3's resolver interprets that prefix as a request for a video stream even when the
// video UI is disabled. Strip the video prefix from the media source cache key before the resolver runs.
val audioOnlyCoreSource = layout.projectDirectory.file(
    "core/media/media3/src/main/java/com/maxrave/media3/service/mediasourcefactory/MergingMediaSourceFactory.kt",
).asFile
val patchAudioOnlyCore = tasks.register("patchAudioOnlyCore") {
    doLast {
        val sourceFile = audioOnlyCoreSource
        if (!sourceFile.isFile) {
            throw GradleException("Audio-only core patch target is missing: ${sourceFile.path}")
        }
        val original = sourceFile.readText()
        val guardMarker = "// Audio-only build guard for legacy video queue items."
        if (!original.contains(guardMarker)) {
            val anchor = "        val getVideo = runBlocking(Dispatchers.IO) { dataStoreManager.watchVideoInsteadOfPlayingAudio.first() } == DataStoreManager.Values.TRUE\n"
            if (!original.contains(anchor)) {
                throw GradleException(
                    "The core media-source factory changed; refusing to build without verifying the audio-only patch.",
                )
            }
            val patch = """
        // Audio-only build guard for legacy video queue items.
        if (!getVideo && mediaItem.mediaId.startsWith(MERGING_DATA_TYPE.VIDEO)) {
            val audioOnlyId = mediaItem.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
            val audioOnlyItem = mediaItem.buildUpon()
                .setCustomCacheKey(audioOnlyId)
                .build()
            return defaultMediaSourceFactory.createMediaSource(audioOnlyItem)
        }
"""
            sourceFile.writeText(original.replace(anchor, anchor + patch))
        }

        val playerSourceFile = rootProject.file(
            "core/media/media3/src/main/java/com/maxrave/media3/exoplayer/CrossfadeExoPlayerAdapter.kt",
        )
        if (!playerSourceFile.isFile) {
            throw GradleException("Audio-only player patch target is missing: ${playerSourceFile.path}")
        }
        val playerOriginal = playerSourceFile.readText()
        val playerGuardMarker = "// Audio-only build guard: every crossfade and precache player is audio-only."
        if (!playerOriginal.contains(playerGuardMarker)) {
            val bufferAnchor = """                        .setBufferDurationsMs(
                            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * 4,
                            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * 4,
                            0,
                            0,
                        ).build(),"""
            if (!playerOriginal.contains(bufferAnchor)) {
                throw GradleException("The core player buffer setup changed; refusing to apply an unverified data-saving patch.")
            }
            val bufferReplacement = """                        .setBufferDurationsMs(
                            30_000,
                            60_000,
                            1_000,
                            2_000,
                        ).build(),"""
            val playerAnchor = """                .setRenderersFactory(perPlayerRenderers)
                .build()

        // The output the user picked"""
            if (!playerOriginal.contains(playerAnchor)) {
                throw GradleException("The core player builder changed; refusing to apply an unverified audio-only patch.")
            }
            val playerReplacement = """                .setRenderersFactory(perPlayerRenderers)
                .build()

        // Audio-only build guard: every crossfade and precache player is audio-only.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        // The output the user picked"""
            val patchedPlayer = playerOriginal
                .replace(bufferAnchor, bufferReplacement)
                .replace(playerAnchor, playerReplacement)
            playerSourceFile.writeText(patchedPlayer)
        } else if (playerOriginal.contains("DEFAULT_MIN_BUFFER_MS * 4")) {
            throw GradleException("Audio-only player patch marker exists but the oversized buffer is still configured.")
        }
    }
}

tasks.register<Delete>("Clean") {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        dependsOn(rootProject.tasks.named("patchAudioOnlyCore"))
        compilerOptions {
            if (project.findProperty("enableComposeCompilerReports") == "true") {
                arrayOf("reports", "metrics").forEach {
                    freeCompilerArgs.addAll(
                        listOf(
                            "-P",
                            "plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=${layout.buildDirectory.asFile.get().absolutePath}/compose_metrics",
                        ),
                    )
                }
            }
        }
    }

    // PipePipe and Brave both depend on com.github.TeamNewPipe:nanojson with different commit
    // hashes. Gradle's default resolver picks PipePipe's older 1d9e1aea... commit which lacks
    // JsonArray.streamAsJsonObjects(), causing NoSuchMethodError when Brave's fallback runs at
    // runtime. Force the latest upstream commit (newer than both libs ship) across every module
    // so the merged APK/JAR carries a nanojson with the API both extractors expect.
    configurations.all {
        resolutionStrategy {
            force("com.github.TeamNewPipe:nanojson:c7a6c1c08d16b6d5ecded34758e6415e07be2166")
        }
    }
}