"""BedJet command codec, extracted from Lull's BedJetKit (Swift).

Pure functions: bytes in, bytes out. No Bluetooth code lives here. Transport is up to you.
The V2 protocol is documented in docs/BEDJET_V2_PROTOCOL.md; the BedJet 3 GATT layout is in PROTOCOL.md.
"""

from dataclasses import dataclass, field
from enum import IntEnum
import random

# MARK: Shared

class Mode(IntEnum):
    STANDBY = 0
    HEAT = 1
    TURBO = 2
    EXTENDED_HEAT = 3
    COOL = 4
    DRY = 5
    WAIT = 6


# MARK: V2 framing

V2_COMMAND_PREFIX = 0x58
V2_REPLY_PREFIX = 0x59


def v2_checksum(data):
    """Makes the whole packet sum to 0xff."""
    return (~sum(data)) & 0xFF


def v2_frame(kind, payload=b""):
    body = bytes([V2_COMMAND_PREFIX, len(payload) + 1, kind]) + bytes(payload)
    return body + bytes([v2_checksum(body)])


def v2_take_packets(buffer):
    """Split a notification byte stream into packets.

    Returns (packets, leftover). Feed leftover back in with the next notification.
    """
    data = bytes(buffer)
    packets = []
    start = 0
    rest = len(data)
    while True:
        try:
            prefix = data.index(V2_REPLY_PREFIX, start)
        except ValueError:
            break
        if prefix + 1 >= len(data):
            rest = prefix
            break
        length = data[prefix + 1] + 3
        # Real packets are short, so a long length means this 0x59 was data, not a prefix.
        if not (3 < length <= 64):
            start = prefix + 1
            continue
        if prefix + length > len(data):
            rest = prefix
            break
        packet = data[prefix:prefix + length]
        if v2_checksum(packet[:-1]) == packet[-1]:
            packets.append(packet)
            start = prefix + length
        else:
            start = prefix + 1
    return packets, data[rest:]


# MARK: V2 commands

V2_CLEAR_SEQUENCE = v2_frame(0x0C)
V2_RUN_SEQUENCE_NO_CLOCK = v2_frame(0x0D)
V2_BUTTON = {Mode.TURBO: 0x01, Mode.HEAT: 0x02, Mode.COOL: 0x03}
V2_STATUS_CODE = {Mode.TURBO: 1, Mode.HEAT: 2, Mode.COOL: 3}
V2_HEAT_RANGE = range(44, 81)  # half degrees C
V2_SEQUENCE_FIRMWARE = 48
V2_MAX_STEPS = 31


def v2_info_request():
    return v2_frame(0x0B)


def v2_name_request():
    return v2_frame(0x06, [0x02, 16])


def v2_button(mode, current=Mode.STANDBY):
    """Modes are buttons. Pressing the current mode's button turns the air off.

    Returns None when pressing it would do nothing.
    """
    if mode == current:
        return None
    target = current if mode == Mode.STANDBY else mode
    if target not in V2_BUTTON:
        return None
    return v2_frame(0x01, [V2_BUTTON[target]])


def v2_settings(led_on, muted):
    return v2_frame(0x11, [(0x01 if muted else 0) | (0 if led_on else 0x02)])


def v2_scene(status, temperature=None, fan_step=None, seconds=None):
    """Temperature, fan and timer go out together, so unchanged values are repeated from the last status.

    `status` is a dict with: mode, fan_step, target, remaining, muted.
    """
    remaining = max(status["remaining"] if seconds is None else seconds, 0)
    target = status["target"] if temperature is None else temperature
    fan = status["fan_step"] if fan_step is None else fan_step
    target_byte = min(max(target, 0), 0x7F) | (0x80 if status.get("muted") else 0)
    return v2_frame(0x0E, [
        V2_STATUS_CODE.get(status["mode"], 0),
        min(max(fan, 0), 19) + 1,
        target_byte,
        min(remaining // 3600, 12),
        (remaining // 60) % 60,
        remaining % 60,
    ])


def v2_write_step(mode, fan_step, temperature, minutes, index, extended):
    """One sequence step. Only Turbo, Heat, Cool and Wait exist on a V2."""
    codes = {Mode.WAIT: 0, Mode.TURBO: 1, Mode.HEAT: 2, Mode.COOL: 3}
    if mode not in codes:
        raise ValueError("A BedJet V2 doesn't have Gentle heat or Dry.")
    if mode == Mode.HEAT:
        target = min(max(temperature, V2_HEAT_RANGE.start), V2_HEAT_RANGE.stop - 1)
    elif mode == Mode.COOL:
        target = 40
    else:
        target = 80
    payload = [0x80 + index, 7 if extended else 6, codes[mode],
               min(max(fan_step, 0), 19) + 1, minutes // 60, minutes % 60, 0, target]
    if extended:
        payload.append(0)
    return v2_frame(0x05, payload)


def v2_tag_sequence(tag=None):
    """Ten bytes the official app stores with a sequence. Any 10 bytes work; random is what the app does."""
    tag = tag if tag is not None else bytes(random.randrange(256) for _ in range(8))
    return v2_frame(0x05, [0x03, 0x0A] + list(tag)[:10] + [0] * max(0, 10 - len(tag)))


def v2_run_sequence(time=None, extended=False):
    """Start the stored sequence. Newer units also want the time of day as (hour, minute, second)."""
    if not extended or time is None:
        return V2_RUN_SEQUENCE_NO_CLOCK
    return v2_frame(0x0D, list(time))


# MARK: V2 replies

@dataclass
class V2Status:
    mode: Mode
    fan_step: int          # 0...19; fan runs at (fan_step + 1) * 5 percent
    actual: int            # half degrees C
    target: int            # half degrees C
    remaining: int         # seconds
    led_on: bool
    muted: bool
    program_step: int      # 0 when no sequence is running
    shutdown_reason: int = 0

    @property
    def fan_percent(self):
        return (self.fan_step + 1) * 5


def v2_parse_status(packet):
    b = packet
    if len(b) < 14 or b[0] != V2_REPLY_PREFIX or b[2] != 0x02:
        return None
    code = (b[4] >> 5) & 0x07
    mode = {0: Mode.STANDBY, 1: Mode.TURBO, 2: Mode.HEAT, 3: Mode.COOL}.get(code)
    if mode is None:
        return None
    return V2Status(
        mode=mode,
        fan_step=max((b[4] & 0x1F) - 1, 0),
        actual=b[3] & 0x7F,
        target=b[7] & 0x7F,
        remaining=(b[5] >> 4) * 3600 + (((b[5] << 2) & 0x3C) | (b[6] >> 6)) * 60 + (b[6] & 0x3F),
        led_on=b[3] & 0x80 == 0,
        muted=b[8] & 0x80 != 0,
        program_step=(b[10] >> 3) & 0x1F,
        shutdown_reason=b[12] if b[1] > 10 else 0,
    )


def v2_parse_info(payload):
    """Returns (protocol_version, firmware, extended_sequences) or None."""
    if len(payload) < 2:
        return None
    firmware = payload[1]
    newer = len(payload) > 7 and payload[7] & 0x01 != 0
    return payload[0], firmware, newer and firmware >= 53


def v2_reply_payload(query, packet):
    """Payload of the reply to `query` (0x0b for info, 0x02 for name), or None if it's something else."""
    if len(packet) < 4 or packet[0] != V2_REPLY_PREFIX:
        return None
    if query == 0x0B:
        return packet[3:-1] if packet[2] == 0x01 else None
    if packet[2] != 0x05 or len(packet) < 5 or packet[3] != query:
        return None
    return packet[4:-1]


# MARK: BedJet 3 commands (GATT characteristic 0x2004 unless noted)

def v3_set_mode_button(mode):
    """Button codes for the BedJet 3. Standby is shown as 'off' in the app."""
    return {Mode.STANDBY: 0x01, Mode.COOL: 0x02, Mode.HEAT: 0x03, Mode.TURBO: 0x04,
            Mode.DRY: 0x05, Mode.EXTENDED_HEAT: 0x06}.get(mode)


def v3_set_mode(mode):
    code = v3_set_mode_button(mode)
    return None if code is None else bytes([0x01, code])


def v3_set_runtime(minutes):
    return bytes([0x02, minutes // 60, minutes % 60])


def v3_set_temperature(half_degrees):
    return bytes([0x03, half_degrees & 0xFF])


def v3_set_fan(step):
    return bytes([0x07, min(max(step, 0), 19)])


def v3_set_clock(hour, minute):
    return bytes([0x08, hour, minute])


def v3_set_led(on):
    return bytes([0x01, 0x46 if on else 0x47])


def v3_set_muted(muted):
    return bytes([0x01, 0x48 if muted else 0x49])


def v3_start_program(slot):
    return bytes([0x01, 0x80 + slot])


def v3_delete_program(slot):
    return bytes([0x01, 0x83 + slot])


V3_ACK_NOTIFICATION = bytes([0x01, 0x52])


def v3_name_program(slot, name):
    """19 bytes: 0x40, slot, 0x10, then up to 15 ASCII bytes, zero padded."""
    ascii_name = "".join(c for c in name if 32 <= ord(c) < 127)[:15].encode("ascii")
    return bytes([0x40, 4 + slot, 0x10]) + ascii_name.ljust(16, b"\0")


def v3_write_program(slot, steps):
    """Each step is (mode, fan_step, temperature, hours, minutes, ends_at_clock). One write per 8 steps."""
    fragments = max(1, (len(steps) + 7) // 8)
    out = []
    for index in range(fragments):
        body = bytearray(43)
        body[0] = 0x40
        body[1] = 7 + slot * 4 + index
        body[2] = len(steps)
        for i, (mode, fan, temp, hours, minutes, clock) in enumerate(steps[index * 8:index * 8 + 8]):
            base = 3 + i * 5
            body[base] = int(mode)
            body[base + 1] = min(max(fan, 0), 19)
            body[base + 2] = temp & 0xFF
            body[base + 3] = (hours & 0x7F) | (0x80 if clock else 0)
            body[base + 4] = minutes
        out.append(bytes(body))
    return out


# MARK: BedJet 3 status (characteristic 0x2000)

@dataclass
class V3Status:
    remaining: int
    actual: int
    target: int
    mode: Mode
    fan_step: int
    max_runtime_minutes: int
    min_temperature: int
    max_temperature: int
    ambient: int
    shutdown_reason: int
    led_on: bool = None
    muted: bool = None
    program_step: int = None
    notification: int = None


def v3_parse_status(b):
    if len(b) < 20:
        return None
    try:
        mode = Mode(b[9])
    except ValueError:
        return None
    status = V3Status(
        remaining=b[4] * 3600 + b[5] * 60 + b[6],
        actual=b[7], target=b[8], mode=mode, fan_step=b[10],
        max_runtime_minutes=b[11] * 60 + b[12],
        min_temperature=b[13], max_temperature=b[14],
        ambient=b[17], shutdown_reason=b[18],
    )
    if len(b) >= 30:
        status.led_on = b[27] & 0x10 != 0
        status.muted = b[27] & 0x01 != 0
        status.program_step = b[28]
        status.notification = b[29]
    return status
