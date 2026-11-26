package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests: archive path and manifest validation must not require Android/Robolectric. */
class SkillPackagePolicyTest {
    @Test fun acceptsSafeRelativePathsAndRejectsTraversalAndPlatformSpecificPaths() {
        assertEquals(listOf("docs", "guide.md"), SkillPackagePolicy.validateRelativePath("docs/guide.md"))
        assertEquals(".", SkillPackagePolicy.normalizeSelectionPath("."))
        assertEquals("docs/guide.md", SkillPackagePolicy.normalizeSelectionPath(" docs/guide.md/"))
        assertNull(SkillPackagePolicy.normalizeSelectionPath("../escape"))
        listOf("../escape", "/absolute", "C:/drive", "a\\b", "a//b", "a/./b", "a/../b", "bad\u0001name")
            .forEach { assertNull("must reject $it", SkillPackagePolicy.validateRelativePath(it)) }
    }

    @Test fun collisionKeyNormalizesUnicodeAndCase() {
        assertEquals(
            SkillPackagePolicy.collisionKey("Café/Guide.md"),
            SkillPackagePolicy.collisionKey("Cafe\u0301/guide.MD"),
        )
    }

    @Test fun validatesSkillFrontmatterWithoutAndroidRuntime() {
        val parsed = SkillPackagePolicy.validateSkillMetadata(
            "---\nname: local-helper\ndescription: A local helper.\n---\n# Guide\n",
        )
        assertEquals("local-helper", parsed?.name)
        assertEquals("A local helper.", parsed?.description)
        assertNull(SkillPackagePolicy.validateSkillMetadata("name: missing delimiters"))
        assertNull(SkillPackagePolicy.validateSkillMetadata("---\nname: Bad_Name\ndescription: no\n---"))
        assertNull(SkillPackagePolicy.validateSkillMetadata("---\nname: valid\ndescription: \n---"))
    }
    @Test fun preservesQuotedAndFoldedSkillDescriptions() {
        val quoted = SkillPackagePolicy.validateSkillMetadata(
            "---\nname: quoted-skill\ndescription: \"Quoted description.\"\n---",
        )
        assertEquals("Quoted description.", quoted?.description)
        val folded = SkillPackagePolicy.validateSkillMetadata(
            "---\nname: folded-skill\ndescription: >\n  First sentence\n  second sentence\n---",
        )
        assertEquals("First sentence second sentence", folded?.description)
        assertEquals("First sentence second sentence", SkillParser.parseSimpleFrontmatter(
            "name: folded-skill\ndescription: >\n  First sentence\n  second sentence",
        )["description"])
    }

}
