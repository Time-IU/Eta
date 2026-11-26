plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(25)
    sourceSets {
        main {
            kotlin.srcDir("../app/src/main/kotlin/io/github/mangi/eta/agent/skill")
            kotlin.include("SkillPackagePolicy.kt", "SkillFrontmatterParser.kt", "SkillParser.kt", "ParsedSkillFile.kt", "SkillPackageLimits.kt", "SkillArchiveExtractor.kt", "SkillInstallationModels.kt", "SkillArchiveSource.kt")
        }
        test {
            kotlin.srcDir("src/test/kotlin")
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
