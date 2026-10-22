# BedJet commands: quick reference

Extracted from Lull's `BedJetKit` (Swift). Full V2 notes, sources and confidence levels are in
`docs/BEDJET_V2_PROTOCOL.md`. Code: `bedjet.py`, checked by `test_bedjet.py`.

The transport is up to you. Anything that can write bytes to the unit and read its notifications will work.

## Connecting (BLE)

The BedJet accepts one connection. While it's connected it stops advertising, so a second phone can't see it.

Scan for a peripheral whose advertised name starts with `BEDJET`. Then look at its services:

| Generation | Service | Write (commands) | Notify (status) | Read (replies) |
| --- | --- | --- | --- | --- |
| BedJet 3 | `0000 1000-BED0-0080-AA55-4265644A6574` | `…2004…` | `…2000…` | `…2006…` |
| BedJet V2 | `49535343-FE7D-4AE5-8FA9-9FAFD205E455` (Microchip UART) | `49535343-8841-43F4-A8D4-ECBE34729BB3` | `49535343-1E4D-4BD9-BA61-23C647249616` | — |

BedJet 3 UUIDs use the full form `0000XXXX-BED0-0080-AA55-4265644A6574`, where `XXXX` is the short code.

For a BedJet 3, also read `…2001…` for the unit's name. Subscribe to notifications on `…2000…` for status.

On a V2, every command and reply goes through the UART characteristics. Notifications arrive as a byte stream, so
split it into packets before reading them (`v2_take_packets`).

## Hand-over between phones (optional)

Lull lets two phones share one BedJet. A phone that wants the unit advertises a request UUID for 8 seconds. That
UUID is `SHA-256("lull.handoff." + name)`, the first 16 bytes, with version 5 and variant bits set. The phone holding
the unit disconnects when it sees the request. You don't need this for a single-phone UI.

## BedJet V2

Frame: `58 len type payload… checksum`. `len` is the count of type plus payload. `checksum = ~sum(all previous bytes) & 0xff`,
so every byte sums to `0xff`. Replies start with `59`.

| Command | Bytes | What it does |
| --- | --- | --- |
| Info | `58 01 0b` | Reply gives protocol, firmware, sequence format. |
| Read name | `58 03 06 02 10` | Reply carries the name. |
| Button | `58 02 01 b` | b: 1 Turbo, 2 Heat, 3 Cool. Pressing the current mode turns the air off. |
| Settings | `58 02 11 f` | f bit 0 = mute beeps, bit 1 = LEDs off. |
| Scene | `58 07 0e mode fan target h m s` | Sets mode, fan, temp, timer together. mode 0 off, 1 Turbo, 2 Heat, 3 Cool. fan = percent÷5, 1–20. target = half °C, bit 7 = muted. |
| Clear sequence | `58 01 0c` | Empties the stored sequence. |
| Write step | `58 0a/09 05 80+n …` | See below. |
| Tag sequence | `58 0d 05 03 0a` + 10 bytes | Labels the sequence. |
| Run sequence | `58 01 0d` or `58 04 0d h m s` | Starts it. The newer format needs the time of day. |

**Sequence step, written as type `05`, param `80+n`:**

- Older format: `80+n, 6, mode, fan, hours, minutes, 0, target`
- Newer format: `80+n, 7, mode, fan, hours, minutes, 0, target, 0`
- Mode: 0 Wait, 1 Turbo, 2 Heat, 3 Cool. Fan is 1–20. Heat target is 44–80 half °C. The unit ignores Cool and
  Turbo targets, but the official app stores 40 and 80.
- A V2 has no Gentle heat or Dry. Sequences need firmware 48 or later. The newer format is used when bit 0 of the
  info reply's byte 7 is set and firmware is 53 or later.
- Write them in order: clear, each step with 200 ms between writes, tag, run.

**Status** (type `02`, sent continuously):

| Byte | Meaning |
| --- | --- |
| 3 | Air temperature, half °C, bits 0–6. Bit 7 = LEDs off. |
| 4 | Mode in bits 5–7. Fan step + 1 in bits 0–4. |
| 5–6 | Timer: hours = b5>>4; minutes = ((b5<<2)&0x3c) \| b6>>6; seconds = b6&0x3f. |
| 7 | Target, half °C, bits 0–6. |
| 8 | Bit 7 = beeps muted. |
| 10 | Running sequence step, bits 3–7 (0 = none). |
| 12 | Shutdown reason, when the packet is longer than 10 bytes. |

Heat limits on a V2: 44–80 half °C. Turbo is fixed at 86, and runs at most 10 minutes. Cool is fixed at 44, and
runs at most 12 hours. Heat run length follows a table the official app enforces (see `longestMinutes` in
`Models.swift`).

## BedJet 3

Commands go to `…2004…`. Single-byte writes are fine. Status arrives on `…2000…`.

| Command | Bytes | What it does |
| --- | --- | --- |
| Mode button | `01 b` | b: 1 off, 2 Cool, 3 Heat, 4 Turbo, 5 Dry, 6 Extended heat |
| Runtime | `02 h m` | Shut-off timer |
| Temperature | `03 t` | Half °C |
| Fan | `07 s` | Step 0–19 |
| Clock | `08 h m` | Time of day |
| LED on / off | `01 46` / `01 47` | |
| Mute / unmute | `01 48` / `01 49` | |
| Start program | `01 (80+slot)` | Slot 0–2 |
| Delete program | `01 (83+slot)` | |
| Ack notification | `01 52` | Clears the unit's notification |
| Name program | `40 (4+slot) 10` + 16 bytes | ASCII, up to 15 characters |
| Write steps | `40 (7+slot*4+frag) count` + 5 bytes per step | 8 steps per write |

Status fields (bytes): 4–6 timer (h, m, s), 7 actual °C, 8 target, 9 mode, 10 fan step,
27 bit 4 = LEDs on, bit 0 = muted, 28 program step, 29 notification code.

## Verification

`python3 -m unittest test_bedjet` (run from this folder) passes 15 tests. Those tests use the byte fixtures from
`CodecV2Tests.swift` and `CodecTests.swift`, so they check the same packets the Swift tests check. The BedJet 3
program-writing path (`v3_write_program`) is only partly covered by tests, and it has not been tried on hardware.
