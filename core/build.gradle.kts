import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // The sandbox path contract tests live in testFixtures, and the app's AndroidSandbox runs the same suite
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

// Test code uses the experimental coroutine test scheduler; keep the opt-in out of production sources.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("Test")) {
        compilerOptions.optIn.set(
            listOf(
                "kotlinx.coroutines.ExperimentalCoroutinesApi",
                "kotlinx.serialization.ExperimentalSerializationApi",
            ),
        )
    }
}

val traceSchemas by configurations.creating { isTransitive = false }
val extractTraceSchemas by tasks.registering(Sync::class) {
    from({ zipTree(traceSchemas.singleFile) }) {
        include("opentelemetry/proto/common/v1/common.proto", "opentelemetry/proto/resource/v1/resource.proto",
            "opentelemetry/proto/trace/v1/trace.proto", "opentelemetry/proto/collector/trace/v1/trace_service.proto")
    }
    into(layout.buildDirectory.dir("trace-schemas"))
}
sourceSets.main { proto.srcDir(extractTraceSchemas) }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().configureEach { builtins { named("java") { option("lite") } } }
    }
}

dependencies {
    implementation(libs.protobuf.lite)
    traceSchemas(libs.otel.proto)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // TLS 1.3 and exported keys in tests: use the same TLS implementation the Android platform is based on, test classpath only
    testImplementation(libs.conscrypt.openjdk.uber)
    testFixturesImplementation(libs.junit)
}

// The resource directories cannot be listed at runtime: at build time scan the headers of knowledge/apps, knowledge/sites, and skills, then generate the index resource after validation; a failed validation fails the build
val knowledgeIndex by tasks.registering(JavaExec::class) {
    val resources = file("src/main/resources")
    val out = layout.buildDirectory.dir("generated/knowledge-index")
    inputs.dir(resources.resolve("knowledge")).withPropertyName("knowledge")
    inputs.dir(resources.resolve("skills")).withPropertyName("skills")
    outputs.dir(out)
    classpath = sourceSets.main.get().output.classesDirs + configurations.runtimeClasspath.get()
    mainClass.set("com.wangzan101.mika.core.learn.knowledge.KnowledgeIndex")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(resources.absolutePath, out.get().file("knowledge/index.json").asFile.absolutePath)
    })
}
sourceSets.main { resources.srcDir(knowledgeIndex) }

// The parts of the README that change (built-in knowledge, provider presets) are regenerated from the source; see ReadmeSections in testFixtures; ReadmeSectionsTest guards that both sides stay consistent
val updateReadme by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Regenerate the sections between the generated markers in README.md"
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("com.wangzan101.mika.core.docs.ReadmeSections")
    args(rootProject.projectDir.absolutePath)
    outputs.upToDateWhen { false }
}

// Walk the whole "Sign in with ChatGPT" flow on desktop against the real OpenAI service (sign-in, list models, Responses, tool calls, token refresh); see docs/dev/chatgpt-signin-check.md.
// A separate source set: it is not part of core's artifacts, nor part of the app
val chatgptCheck by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[chatgptCheck.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[chatgptCheck.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

val chatgptSignInCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run the ChatGPT sign-in flow with the app's sign-in code against OpenAI; -Ptimeout=<seconds> -Pmodel=<slug> -Pkeep"
    classpath = chatgptCheck.runtimeClasspath
    mainClass.set("com.wangzan101.mika.core.model.chatgpt.check.ChatGptSignInCheckKt")
    args(listOfNotNull(
        providers.gradleProperty("timeout").orNull?.let { "--timeout=$it" },
        providers.gradleProperty("model").orNull?.let { "--model=$it" },
        if (providers.gradleProperty("keep").isPresent) "--keep" else null,
    ))
    outputs.upToDateWhen { false }
}

// Tests that read repo files outside core (docs, README, app sources and resources, eval task set). They are a separate task with only these inputs:
// when only app or docs change, the rest of core's tests stay UP-TO-DATE instead of rerunning the whole suite. test depends on this task, so ./gradlew :core:test still runs everything.
val repoTestClasses = listOf(
    "**/core/DocsConsistencyTest*",
    "**/docs/ReadmeSectionsTest*",
    "**/docs/ReadmeTranslationsTest*",
    "**/capability/PackageDependencyTest*",
    "**/action/SingleEntryTest*",
    "**/prompt/RegexPortabilityTest*",
    "**/general/MemoryRequestsTest*",
    "**/eval/TaskSetTest*",
    "**/eval/RescoreEvalRun*",
)

val repoTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Consistency tests over repo docs, README, app sources and the eval task set"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    // testFixtures puts core's jar ahead of the test classpath; keep the directories first so tests that read resources by directory find their files
    classpath = sourceSets.main.get().output + sourceSets.test.get().runtimeClasspath
    include(repoTestClasses)
    // TaskSetTest reads the eval task set at the repo root
    inputs.dir(rootProject.file("eval")).withPropertyName("evalTasks")
    // DocsConsistencyTest and ReadmeTranslationsTest read the repository documentation (README.md in the root, and the per-language translations under docs/readme/ which live in docs) and compare it against the app sources; a change to the documentation or app sources requires a rerun
    inputs.files(rootProject.file("README.md"), rootProject.file("tools/sandbox/README.md")).withPropertyName("docsFiles")
    inputs.dir(rootProject.file("docs")).withPropertyName("docsDir")
    inputs.dir(rootProject.file("app/src")).withPropertyName("appSources")
    // updateReadme rewrites README first, so tests in the same build read the rewritten version
    mustRunAfter(updateReadme)
}

tasks.test {
    // Cross-language CLI contracts run explicitly through cliTest; pure JVM tests do not require Python on the computer.
    exclude("**/RemoteCliContractTest*")
    // The real-gateway cross-language test runs explicitly; ordinary JVM tests do not require Go.
    exclude("**/RelayGatewayIntegrationTest*")
    // Tests that read repo files live in repoTest (see above)
    exclude(repoTestClasses)
    dependsOn(repoTest)
    // testFixtures puts core's jar ahead of the test classpath; keep the directories first so tests that read resources by directory find their files
    classpath = sourceSets.main.get().output + classpath
    // updateReadme rewrites README first; tests in the same build must read the rewritten version
    mustRunAfter(updateReadme)
    // Message-sequence goldens for loop scenarios; regenerate with -PupdateSnapshots (see LoopSnapshot)
    systemProperty("mika.snapshotDir", file("src/test/resources/loop-snapshots").absolutePath)
    // Goldens for the prompt surface (system prompts, tool definitions, feedback text); likewise regenerated with -PupdateSnapshots (see PromptSurface)
    systemProperty("mika.promptSurfaceDir", file("src/test/resources/prompt-surface").absolutePath)
    val updateSnapshots = project.hasProperty("updateSnapshots")
    inputs.property("updateSnapshots", updateSnapshots)
    if (updateSnapshots) {
        systemProperty("mika.updateSnapshots", "true")
        outputs.upToDateWhen { false }
    }
}

val relayGatewayBinary = tasks.register<Exec>("relayGatewayBinary") {
    group = "verification"
    description = "Build the self-hosted remote MCP gateway for cross-language integration tests"
    val output = layout.buildDirectory.file("relay-check/mika-relay")
    workingDir(rootProject.file("companions/relay"))
    inputs.files(fileTree(rootProject.file("companions/relay")) { include("*.go", "go.mod", "go.sum", "web/**") })
    outputs.file(output)
    environment("GOCACHE", rootProject.layout.buildDirectory.dir("go-cache").get().asFile.absolutePath)
    doFirst { output.get().asFile.parentFile.mkdirs() }
    commandLine("go", "build", "-trimpath", "-o", output.get().asFile.absolutePath, ".")
}

tasks.register<Test>("relayTest") {
    group = "verification"
    description = "Real Go gateway connects to the Kotlin phone tunnel to verify the full remote CLI / MCP path"
    dependsOn(relayGatewayBinary)
    inputs.file(layout.buildDirectory.file("relay-check/mika-relay")).withPropertyName("relayGatewayBinary")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/RelayGatewayIntegrationTest*")
    systemProperty("mika.relayBinary", layout.buildDirectory.file("relay-check/mika-relay").get().asFile.absolutePath)
}

val cliContractTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Real Python CLI against the Kotlin task API, verifying it only uses /api/v1"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.main.get().output + sourceSets.test.get().runtimeClasspath
    include("**/RemoteCliContractTest*")
    inputs.dir(rootProject.file("companions/mika/scripts"))
    systemProperty("mika.python", providers.gradleProperty("mikaPython").getOrElse("python3"))
}

// Requires Python 3.10+ and is not hooked into check; run ./gradlew :core:cliTest explicitly when needed
val cliTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "CLI process, fake ADB, standalone Skill and real HTTP contract tests (Python 3.10+)"
    dependsOn(cliContractTest)
    workingDir(rootProject.projectDir)
    commandLine(providers.gradleProperty("mikaPython").getOrElse("python3"), "-m", "unittest", "discover", "-s", "companions/mika/tests", "-v")
}
