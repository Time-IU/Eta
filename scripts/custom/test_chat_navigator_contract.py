#!/usr/bin/env python3
"""Source-contract regressions, NOT Compose/device tests. Run with python3 -m unittest."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "app/src/main/kotlin/io/github/mangi/eta"
BODY = KOTLIN / "ui/components/AgentChatBody.kt"


def call(source, name):
    match = re.search(r"(?<!fun )\b" + re.escape(name) + r"\(", source)
    if not match:
        raise AssertionError(f"Missing call: {name}")
    start = match.end()
    depth = 1
    for end in range(start, len(source)):
        depth += (source[end] == "(") - (source[end] == ")")
        if depth == 0:
            return source[start:end]
    raise AssertionError(f"Unclosed call: {name}")


class NavigatorWiringContract(unittest.TestCase):
    def test_callback_required_at_all_three_chat_layers(self):
        source = BODY.read_text()
        self.assertEqual(3, len(re.findall(r"onOpenNavigator: \(\) -> Unit,", source)))
        self.assertNotRegex(source, r"onOpenNavigator: \(\) -> Unit\s*=")

    def test_scaffold_forwards_callback_to_bottom_bar(self):
        self.assertIn("onOpenNavigator = onOpenNavigator", call(BODY.read_text(), "AgentChatBottomBar"))

    def test_all_other_production_edges_remain_wired(self):
        body = BODY.read_text()
        home = (KOTLIN / "ui/screens/home/AgentHomeScreen.kt").read_text()
        root = (KOTLIN / "ui/app/AgentAppRoot.kt").read_text()
        self.assertIn("OpenConversationNavigator", call(home, "AgentChatBody"))
        self.assertIn("onOpenNavigator = onOpenNavigator", call(body, "AgentChatScaffold"))
        self.assertIn("onOpen = onOpenNavigator", call(body, "ChatNavigatorHandle"))
        self.assertRegex(root, r"OpenConversationNavigator\s*->\s*\{[^}]*conversationNavigatorOpen\s*=\s*true")

    def test_full_width_positioner_is_not_the_touch_target(self):
        path = KOTLIN / "ui/components/ChatNavigatorHandle.kt"
        source = path.read_text() if path.exists() else BODY.read_text().split("private fun ChatNavigatorHandle", 1)[-1]
        self.assertRegex(source, r"\.size\(48\.dp\)")
        positioner = source.split(".fillMaxWidth()", 1)[1].split("contentAlignment", 1)[0]
        self.assertNotIn("clickable", positioner)
        self.assertNotIn("pointerInput", positioner)

    def test_pointer_input_uses_latest_callback_and_density_key(self):
        path = KOTLIN / "ui/components/ChatNavigatorHandle.kt"
        source = path.read_text() if path.exists() else BODY.read_text().split("private fun ChatNavigatorHandle", 1)[-1]
        self.assertIn("rememberUpdatedState(onOpen)", source)
        self.assertIn(".pointerInput(triggerPx)", source)
        self.assertNotIn("coerceAtMost(0f)", source)

    def test_coach_does_not_install_fullscreen_pointer_interceptor(self):
        source = (KOTLIN / "ui/onboarding/GestureCoach.kt").read_text()
        code = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
        self.assertNotRegex(code, r"\.(pointerInput|clickable)\(")
        self.assertNotIn("Dialog(", code)
        self.assertIn("onClick = onSkip", code)
        self.assertIn("BackHandler", code)

    def test_coach_back_handler_is_safe_without_dispatcher_owner(self):
        source = (KOTLIN / "ui/onboarding/GestureCoach.kt").read_text()
        self.assertIn("LocalOnBackPressedDispatcherOwner.current", source)
        self.assertRegex(source, r"if \(backOwner != null\)\s*\{\s*BackHandler")

    def test_only_current_visible_step_can_complete(self):
        source = (KOTLIN / "ui/onboarding/OnboardingHost.kt").read_text()
        self.assertNotIn("navigatorEverOpened", source)
        self.assertIn("onHome &&", source)
        self.assertIn("navigatorOpen", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
