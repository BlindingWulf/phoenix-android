plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.register<JavaExec>("acceptance") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.phoenix.core.CoreAcceptance")
}
listOf(
    "provisionAcceptance" to "org.phoenix.core.provision.ProvisioningHarness",
    "worldAcceptance" to "org.phoenix.core.world.WorldImporterHarness",
    "networkingAcceptance" to "org.phoenix.core.network.MinecraftDnsAcceptance",
    "netbirdAcceptance" to "org.phoenix.core.network.netbird.NetBirdAcceptance",
    "byopAcceptance" to "org.phoenix.core.network.netbird.NetBirdByopAcceptance",
    "playitAcceptance" to "org.phoenix.core.playit.PlayitClaimClientTest"
).forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set(entryPoint)
        if (taskName == "provisionAcceptance") args(rootProject.file("DEPENDENCY_MANIFEST.json").absolutePath)
        if (taskName == "byopAcceptance") args(rootProject.file("deploy/oracle-proxy").absolutePath)
    }
}
