package io.github.mangi.eta.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarFeedbackContractTest {
    private fun source(relative: String): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/kotlin").isDirectory }
        return File(root, "app/src/main/kotlin/io/github/mangi/eta/$relative").readText()
    }

    @Test fun couiCircularToolbarShadowUsesSmoothCorners() {
        val source = source("ui/components/coui/CouiScaffoldPage.kt")
        assertTrue(source.contains(".copy(smoothCorner = true)"))
    }

    @Test fun miuixMenuAndBackFeedbackAreClippedToRoundedShapes() {
        val menu = source("ui/app/AgentAppShell.kt")
        val menuButton = menu.substringAfter("private fun ChatTopBarMenuButton(")
        assertTrue(menuButton.contains(".clip(RoundedCornerShape(12.dp)).clickable("))

        val back = source("ui/components/MiuixBackButton.kt")
        assertTrue(back.contains("modifier.clip(RoundedCornerShape(12.dp))"))
    }
}
