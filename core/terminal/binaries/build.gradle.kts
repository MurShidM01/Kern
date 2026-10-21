import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * Ships the Termux `proot` runtime (and its dependencies) as `lib*.so` in `jniLibs`.
 *
 * Android only keeps files under `lib/<abi>/lib*.so` executable — the native library directory is the
 * single location where an app targeting API 29+ may `execve` a binary. Bundling proot here is what
 * lets Kern run Alpine without root, replacing the app-storage `execve` that Android blocks.
 *
 * The binaries are vendored (committed) so the normal build needs no network. They are produced by
 * `tools/fetch_termux_binaries.py` and pinned in `binaries.lock.json`; [verifyTermuxBinaries] runs on
 * every build and fails loudly if a vendored file is missing or does not match its recorded SHA-256.
 */
android {
    namespace = "com.aliedu.kern.core.terminal.binaries"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        minSdk = 28
        ndk {
            // Real devices are arm64; x86_64 covers emulators.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // proot must exist as a real file in nativeLibraryDir to be executable, so the libs are extracted
    // at install time instead of being loaded straight out of the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

/** Fails the build when a vendored binary is missing or no longer matches `binaries.lock.json`. */
val verifyTermuxBinaries = tasks.register("verifyTermuxBinaries") {
    description = "Verifies the vendored Termux binaries against binaries.lock.json."
    group = "verification"
    // Captured as locals so the execution action stays configuration-cache serializable.
    val lock = layout.projectDirectory.file("binaries.lock.json").asFile
    val root = layout.projectDirectory.dir("src/main/jniLibs").asFile
    inputs.file(lock)
    inputs.dir(root)
    outputs.upToDateWhen { false }

    doLast {
        val abis = (JsonSlurper().parse(lock) as Map<*, *>)["abis"] as Map<*, *>
        val problems = mutableListOf<String>()

        abis.forEach { (abi, entries) ->
            (entries as Map<*, *>).forEach { (name, meta) ->
                val expected = meta as Map<*, *>
                val file = File(File(root, abi.toString()), name.toString())
                val digest = if (file.isFile) {
                    MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                } else {
                    null
                }
                when {
                    digest == null -> problems += "missing $abi/$name"
                    file.length() != (expected["size"] as Number).toLong() ->
                        problems += "size mismatch for $abi/$name"
                    digest != expected["sha256"] -> problems += "sha256 mismatch for $abi/$name"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Vendored Termux binaries failed verification:\n  " + problems.joinToString("\n  ") +
                    "\nRegenerate them with `./gradlew :core:terminal:binaries:fetchTermuxBinaries`."
            )
        }
        logger.lifecycle("Termux binaries verified against binaries.lock.json")
    }
}

/**
 * Regenerates the vendored binaries from the pinned Termux packages. Requires Python 3 and network
 * access; not part of the normal build. Every download is checked against the pinned SHA-256.
 */
val fetchTermuxBinaries = tasks.register<Exec>("fetchTermuxBinaries") {
    description = "Downloads and re-vendors the Termux proot binaries (requires Python 3)."
    group = "verification"
    workingDir = rootProject.projectDir
    val python = providers.environmentVariable("PYTHON").orElse("python3")
    commandLine(python.get(), "core/terminal/binaries/tools/fetch_termux_binaries.py", "--licenses")
}

tasks.named("preBuild") {
    dependsOn(verifyTermuxBinaries)
}
