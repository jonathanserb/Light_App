# Light_App

Control a Raspberry Pi Pico from an Android phone over Bluetooth LE: set its clock, read and
drive its pins, schedule actions by time of day, and run and tune small programs.

Everything you set is stored on the Pico. The phone is a remote control for changing settings;
once configured, the Pico carries on by itself with no connection.

```
android/   the Android app (Kotlin, Jetpack Compose)
pico/      the Pico firmware (MicroPython)
```

## What you need

- A **Raspberry Pi Pico W or Pico 2 W**. The plain Pico / Pico 2 has no Bluetooth radio.
- An Android phone running Android 8.0 or newer.

## 1. Put the firmware on the Pico

1. Install MicroPython (v1.21 or newer) for your board: hold BOOTSEL while plugging in USB and
   copy the `.uf2` from <https://micropython.org/download/> onto the drive that appears.
2. Copy the five `.py` files from `pico/` to the Pico. With
   [Thonny](https://thonny.org): open each file and *File → Save as… → Raspberry Pi Pico*.
   Or with `mpremote`:

   ```bash
   mpremote cp pico/*.py :
   ```

3. Unplug and replug. `main.py` starts automatically and the Pico advertises as **Pico**.

## 2. Get the app

- **Android Studio:** open the `android/` folder and press Run.
- **Command line** (needs the Android SDK):

  ```bash
  cd android && ./gradlew assembleDebug
  ```

  The APK lands in `android/app/build/outputs/apk/debug/app-debug.apk`.
- **No tools at all:** every push builds the APK in GitHub Actions; download
  `pico-light-debug-apk` from the run's Artifacts.

## 3. Use it

Open the app, tap **Scan for devices**, and tap your Pico. Only Picos running this firmware are
listed. The app sets the Pico's clock from the phone each time it connects.

| Tab | What it does |
|---|---|
| **Status** | Pico's clock, running program, uptime, temperature. Rename, set a passcode, reboot. |
| **Pins** | Add the pins you've wired up, give them labels, choose input / output / dimmable PWM, switch and dim them, watch inputs. Read the three analog inputs. |
| **Schedule** | "At 18:30 on weekdays, switch GP15 on" — optionally undoing it after a number of seconds. Can also set a brightness, or start/stop a program. Up to 32. |
| **Programs** | Run or stop the programs in `pico/programs.py`, edit their parameters (saved on the Pico, applied live), and pick one to start at power-up. |
| **Console** | Raw traffic, and a box to send commands by hand. |

### Things to know

- **The clock is lost when the Pico loses power.** It has no battery. After a power cut,
  pins return to their last manually-set state and the power-up program starts, but schedules
  wait until a phone connects and sets the time. If that matters, power it from something
  reliable, or add a DS3231 clock module (not supported by this firmware yet).
- **The passcode is a deterrent, not security.** The link isn't encrypted or paired. Without
  a passcode, anyone nearby with the app can control the Pico.
- GP23–25 and GP29 are used by the wireless chip and aren't available. The on-board `LED` can
  be switched but not dimmed.
- One phone can be connected at a time.

## Writing your own programs

Add a function to `pico/programs.py` and copy the file to the Pico; it shows up in the app
along with its parameters.

```python
@program("porch", "On at a brightness while it's dark", pin=15, level=60, dark_below=0.8)
async def porch(ctx):
    while True:
        dark = ctx.adc(0) < ctx.get("dark_below")      # light sensor on ADC0
        ctx.pwm(ctx.get("pin"), ctx.get("level") if dark else 0)
        await ctx.sleep(5)
```

Read parameters with `ctx.get()` inside the loop so edits from the phone take effect while it
runs. The file's docstring lists everything on `ctx`.

## Protocol

Nordic UART Service over BLE; one JSON object per line each way.

```
→ {"id": 7, "cmd": "pin.set", "pin": 15, "value": 1}
← {"id": 7, "ok": true, "pin": {...}}
← {"id": 8, "ok": false, "err": "GP25 is not available"}
← {"ev": "fired", "sched": 3, "name": "Evening on"}        (unsolicited event)
```

| Command | Arguments |
|---|---|
| `info` | – |
| `auth` | `pass` |
| `time.set` | `t`: `[year, month, day, hour, minute, second, weekday]`, local time, Monday = 0 |
| `name.set` / `pass.set` / `reboot` | `name` / `pass` / – |
| `pin.list` | – |
| `pin.cfg` | `pin`, `mode` (`in`, `in_up`, `in_down`, `out`, `pwm`, `off`), optional `label`, `freq` |
| `pin.set` | `pin`, and `value` (0/1) or `duty` (0–100) |
| `pin.get` / `adc.get` | `pin` / `ch` (0–2) |
| `sched.list` / `sched.put` / `sched.del` | – / `item` / `sid` |
| `prog.list` / `prog.run` / `prog.stop` / `prog.boot` | – / `name`, optional `args` / – / `name` or null |
| `param.set` | `key` (`program.param`), `value` (null = back to default) |

A schedule `item`: `{"id", "name", "en", "h", "m", "days" (bit 0 = Monday), "dur" (seconds),
"act": {"type": "pin"|"pwm"|"prog"|"stop", "pin", "value", "duty", "name"}}`.

## Tests

```bash
python3 -m unittest discover pico/tests        # firmware logic, on desktop Python
(cd pico && micropython tests/smoke_micropython.py)   # same, on the MicroPython unix port
(cd android && ./gradlew testDebugUnitTest)    # app protocol code
```
