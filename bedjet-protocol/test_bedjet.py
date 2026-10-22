"""Checks the Python codec against the byte fixtures in BedJetKit's CodecV2Tests.

Run: python3 -m unittest test_bedjet
"""

import unittest

import bedjet
from bedjet import Mode


def h(hex_text):
    return bytes(int(x, 16) for x in hex_text.split())


class V2CommandTests(unittest.TestCase):
    def test_commands_match_captured_bytes(self):
        self.assertEqual(bedjet.v2_info_request(), h("58 01 0b 9b"))
        self.assertEqual(bedjet.v2_name_request(), h("58 03 06 02 10 8c"))
        self.assertEqual(bedjet.v2_button(Mode.HEAT, Mode.STANDBY), h("58 02 01 02 a2"))
        self.assertEqual(bedjet.v2_button(Mode.TURBO, Mode.COOL), h("58 02 01 01 a3"))
        self.assertEqual(bedjet.v2_settings(True, True), h("58 02 11 01 93"))
        self.assertEqual(bedjet.v2_settings(False, False), h("58 02 11 02 92"))

    def test_modes_are_buttons_that_toggle(self):
        self.assertEqual(bedjet.v2_button(Mode.STANDBY, Mode.COOL), h("58 02 01 03 a1"))
        self.assertIsNone(bedjet.v2_button(Mode.COOL, Mode.COOL))
        self.assertIsNone(bedjet.v2_button(Mode.STANDBY, Mode.STANDBY))
        self.assertIsNone(bedjet.v2_button(Mode.DRY, Mode.STANDBY))

    def test_scene_matches_captured_timer_command(self):
        heat = {"mode": Mode.HEAT, "fan_step": 16, "target": 76, "remaining": 3 * 60, "muted": True}
        self.assertEqual(bedjet.v2_scene(heat, seconds=20 * 60), h("58 07 0e 02 11 cc 00 14 00 9f"))

    def test_scene_keeps_timer_when_changing_fan(self):
        cool = {"mode": Mode.COOL, "fan_step": 14, "target": 54, "remaining": 9 * 3600 + 59 * 60 + 55, "muted": True}
        self.assertEqual(bedjet.v2_scene(cool, fan_step=11), h("58 07 0e 03 0c b6 09 3b 37 52"))

    def test_writes_sequence_steps(self):
        self.assertEqual(bedjet.v2_write_step(Mode.HEAT, 7, 68, 30, 1, True), h("58 0a 05 81 07 02 08 00 1e 00 44 00 a4"))
        self.assertEqual(bedjet.v2_write_step(Mode.COOL, 12, 44, 360, 0, False), h("58 09 05 80 06 03 0d 06 00 00 28 d5"))
        with self.assertRaises(ValueError):
            bedjet.v2_write_step(Mode.DRY, 5, 60, 60, 0, True)

    def test_runs_sequence(self):
        self.assertEqual(bedjet.v2_run_sequence((23, 5, 9), extended=True), h("58 04 0d 17 05 09 71"))
        self.assertEqual(bedjet.v2_run_sequence((23, 5, 9), extended=False), h("58 01 0d 99"))

    def test_sequence_matches_the_official_apps_bytes(self):
        # Newer (extended) format, as the Swift test's firmware-53 unit gets.
        steps = [
            (Mode.TURBO, 19, 86, 10),
            (Mode.HEAT, 7, 68, 30),
            (Mode.COOL, 12, 44, 360),
        ]
        commands = [bedjet.V2_CLEAR_SEQUENCE]
        commands += [bedjet.v2_write_step(m, f, t, minutes, i, True) for i, (m, f, t, minutes) in enumerate(steps)]
        self.assertEqual(commands[0], h("58 01 0c 9a"))
        self.assertEqual(commands[1], h("58 0a 05 80 07 01 14 00 0a 00 50 00 a2"))
        self.assertEqual(commands[2], h("58 0a 05 81 07 02 08 00 1e 00 44 00 a4"))
        self.assertEqual(commands[3], h("58 0a 05 82 07 03 0d 06 00 00 28 00 d1"))


class V2ReplyTests(unittest.TestCase):
    def test_parses_captured_off_status(self):
        off = bedjet.v2_parse_status(h("59 0b 02 a7 0c 00 00 41 00 00 00 00 00 a5"))
        self.assertEqual(off.mode, Mode.STANDBY)
        self.assertEqual(off.actual, 0x27)
        self.assertEqual(off.target, 0x41)
        self.assertEqual(off.remaining, 0)
        self.assertFalse(off.led_on)
        self.assertFalse(off.muted)
        self.assertEqual(off.program_step, 0)

    def test_parses_running_status(self):
        cool = bedjet.v2_parse_status(h("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26"))
        self.assertEqual(cool.mode, Mode.COOL)
        self.assertEqual(cool.fan_percent, 60)
        self.assertEqual(cool.remaining, 9 * 3600 + 59 * 60 + 52)
        self.assertEqual(cool.target, 0x36)
        self.assertTrue(cool.muted)
        self.assertTrue(cool.led_on)
        self.assertEqual(cool.program_step, 1)
        self.assertEqual(cool.shutdown_reason, 3)

    def test_reassembles_packets_across_notifications(self):
        packets, rest = bedjet.v2_take_packets(h("00 ff 59 0b 02 34 6c 9e f4"))
        self.assertEqual(packets, [])
        packets, rest = bedjet.v2_take_packets(rest + h("b6 80 00 08 00 03 26 59 12 05 02 42 65 64"))
        self.assertEqual(packets, [h("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 26")])
        packets, rest = bedjet.v2_take_packets(rest + h("72 6f 6f 6d 20 20 20 20 20 20 20 20 20 a5"))
        self.assertEqual(len(packets), 1)
        self.assertEqual(bytes(bedjet.v2_reply_payload(0x02, packets[0])).rstrip(b"\0 "), b"Bedroom")
        self.assertEqual(rest, b"")

    def test_drops_packets_with_bad_checksums(self):
        packets, rest = bedjet.v2_take_packets(h("59 0b 02 34 6c 9e f4 b6 80 00 08 00 03 27"))
        self.assertEqual(packets, [])
        self.assertLess(len(rest), 14)

    def test_parses_info(self):
        packet = h("59 0c 01 02 35 00 00 00 00 00 01 00 00 00 61")
        self.assertEqual(bedjet.v2_parse_info(bedjet.v2_reply_payload(0x0B, packet)), (2, 53, True))
        self.assertIsNone(bedjet.v2_reply_payload(0x02, packet))


class V3Tests(unittest.TestCase):
    def test_runtime_splits_hours_and_minutes(self):
        self.assertEqual(bedjet.v3_set_runtime(150), bytes([0x02, 2, 30]))

    def test_name_is_padded_and_trimmed(self):
        command = bedjet.v3_name_program(1, "A very long schedule name")
        self.assertEqual(len(command), 19)
        self.assertEqual(command[:3], bytes([0x40, 0x05, 0x10]))
        self.assertEqual(command[3:].rstrip(b"\0"), b"A very long sch")

    def test_long_programs_split_into_fragments(self):
        steps = [(Mode.COOL, i, 40, 0, 30, False) for i in range(19)]
        commands = bedjet.v3_write_program(2, steps)
        self.assertEqual([c[1] for c in commands], [15, 16, 17])
        self.assertTrue(all(len(c) == 43 and c[2] == 19 for c in commands))


if __name__ == "__main__":
    unittest.main()
