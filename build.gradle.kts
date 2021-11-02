import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.31"
    application
}

group = "me.youi"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.tika:tika-core:2.1.0")
    implementation("org.simplejavamail:simple-java-mail:6.6.1")
    implementation("org.simplejavamail:smime-module:6.6.1")
    implementation("org.slf4j:slf4j-simple:1.7.32")
    implementation("net.sf.jmimemagic:jmimemagic:0.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2-native-mt")
    implementation("com.squareup.okhttp3:okhttp:4.9.2")
    implementation("commons-cli:commons-cli:1.4")
    implementation("org.jsoup:jsoup:1.14.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.5.31")
}

tasks.test {
    useJUnit()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("MainKt")
}

tasks.register("fatJar", org.gradle.jvm.tasks.Jar::class.java) {
    archiveFileName.set("embed-email.jar")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes("Main-Class" to application.mainClass)
    }
    from(configurations.runtimeClasspath.get()
        .onEach { println("add from dependencies: ${it.name}") }
        .map { if (it.isDirectory) it else zipTree(it) }
    ) {
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }

    val sourcesMain = sourceSets.main.get()
    sourcesMain.allSource.forEach {
        println("add from sources: ${it.name}")
    }
    from(sourcesMain.output)
}
