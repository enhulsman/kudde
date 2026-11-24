#!/usr/bin/env python3
"""Offline checks for the TalkBack speech assertions."""
import pathlib
import runpy
import unittest

runner = runpy.run_path(str(pathlib.Path(__file__).with_name("talkback-phone.py")))
heard = runner["heard"]
require_once = runner["require_once"]


def fragment(text, utterance):
    return f'V talkback: SpeechControllerImpl: Speaking fragment text="{text}", utteranceId={utterance}, TtsSpan=null'


class SpeechTest(unittest.TestCase):
    def test_fragments_of_one_utterance_can_span_title_and_place(self):
        log = "\n".join([fragment("test pane", "talkback_10"), fragment("Test space", "talkback_10")])
        self.assertEqual(heard(log), ["test pane Test space"])
        require_once(heard(log), "test pane", "Test space")

    def test_missing_destination_fails_even_if_back_is_spoken(self):
        with self.assertRaisesRegex(AssertionError, "observed 0"):
            require_once(heard(fragment("Back. Button", "talkback_10")), "test pane", "Test space")

    def test_a_second_reading_with_its_own_id_fails(self):
        logs = "\n".join(fragment("Test pane. Test space", f"talkback_{i}") for i in [10, 11])
        with self.assertRaisesRegex(AssertionError, "observed 2"):
            require_once(heard(logs), "test pane", "Test space")

    def test_other_process_output_and_malformed_speech_are_ignored(self):
        log = 'unrelated: observed pane\n' + fragment("No more terminals in this tab", "talkback_21")
        self.assertEqual(heard(log), ["No more terminals in this tab"])


if __name__ == "__main__":
    unittest.main()
