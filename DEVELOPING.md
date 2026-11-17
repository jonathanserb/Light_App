# Changing the code

The project is two independent halves that only share a small text protocol:

```
pico/      firmware for the Pico (MicroPython)
android/   the phone app (open this folder, not the repo root, in Android Studio)
```

Each half has one file of plain tunable values. Look there first:

| | File | Examples |
|---|---|---|
| Pico | `pico/settings.py` | default name, which pins may be used, schedule limit, advertising interval |
| App | `android/app/src/main/java/com/lightapp/pico/Settings.kt` | timeouts, refresh rates, whether the clock is set on connect |

## Pico firmware (`pico/`)

| File | What it holds | Edit it when |
|---|---|---|
| `settings.py` | Tunable values | Changing a limit, name, pin list or timing |
| `programs.py` | The runnable programs and the `@program` decorator | Adding or changing a program |
| `controller.py` | All the logic: commands, pin state, schedules, saving | Adding a command, changing schedule behaviour |
| `hal_pico.py` | The only file that touches hardware (`Pin`, `PWM`, `ADC`, `RTC`) | Supporting new hardware |
| `ble_uart.py` | Bluetooth: advertising, receiving and sending lines | Rarely |
| `main.py` | Starts everything and runs the loop | Rarely |
| `tests/` | Desktop tests with a fake board; not copied to the Pico | After changing `controller.py` |

`controller.py` is the big one. It is divided by `# ----` banners, listed at the top of the file.

### Recipes

**Add a program.** Add a function to `programs.py`; nothing else changes. It appears in the app
with its parameters.

```python
@program("porch", "On at a brightness while it's dark", pin=15, level=60, dark_below=0.8)
async def porch(ctx):
    while True:
        dark = ctx.adc(0) < ctx.get("dark_below")
        ctx.pwm(ctx.get("pin"), ctx.get("level") if dark else 0)
        await ctx.sleep(5)
```

**Give programs a new ability** (say `ctx.humidity()`). Add a method to `Hal` in `hal_pico.py`
that reads the sensor, then a method to `Ctx` at the top of `controller.py` that calls it.

**Add a command.** In the commands section of `controller.py`:

```python
@command("hum.get")
def _c_hum_get(self, req):
    return {"value": self.hal.humidity()}
```

Try it from the app's Console tab by typing `hum.get` before writing any app code.

**Remember something new about each pin.** Add it to `PIN_DEFAULTS` near the top of
`controller.py`. Pins already saved on a Pico pick up the default.

**Add a schedule action type.** Add its name to `ACTIONS`, handle it in `_fire()`, and validate
its fields in the `sched.put` command.

### Rules that will save you time

- `"id"` in a request is the message number, so never use it as a command argument
  (deleting a schedule uses `"sid"` for that reason).
- If you add a method to `Hal`, add the same one to `FakeHal` in `tests/test_controller.py`.
- Programs must wait with `await ctx.sleep(...)`, never `time.sleep()`, or Bluetooth and
  schedules freeze while they wait.
- After editing, run the tests on your computer before copying to the Pico:

  ```bash
  python3 -m unittest discover pico/tests
  ```

## Android app (`android/app/src/main/java/com/lightapp/pico/`)

Three layers; each only talks to the one below it.

| Layer | File | What it holds |
|---|---|---|
| | `Settings.kt` | Tunable values |
| Screens | `ui/App.kt` | Top bar, bottom tabs (`TABS`), which screen is showing |
| | `ui/ConnectScreen.kt` | Scan list, reconnect button, permission prompts |
| | `ui/StatusTab.kt`, `PinsTab.kt`, `SchedulesTab.kt`, `ProgramsTab.kt`, `ConsoleTab.kt` | One file per tab |
| | `ui/Common.kt` | Shared pieces: `Section` card, `Picker` dropdown, `TextPrompt` dialog, pin mode names |
| | `ui/MainViewModel.kt` | All on-screen state, and one function per user action |
| Protocol | `data/PicoClient.kt` | `request("cmd", "key" to value)`: send a command, wait for the reply |
| | `data/Models.kt` | `DeviceInfo`, `PinState`, `Schedule`, `ProgramInfo` and their JSON conversion |
| Bluetooth | `ble/PicoBle.kt` | Scanning, connecting, sending and receiving lines |
| | `ble/LineFramer.kt` | Joining Bluetooth fragments into lines |

Other places: `MainActivity.kt` (theme colours), `app/src/main/AndroidManifest.xml`
(permissions), `app/src/main/res/` (app name and icon), `app/build.gradle.kts` (version,
libraries).

### How one action flows

Switching a pin on, which everything else copies:

1. The `Switch` in `PinsTab.kt` calls `vm.setPin(pin, true)`.
2. `setPin` in `MainViewModel.kt` calls `client.request("pin.set", …)`, then `loadPins()`.
3. `loadPins()` requests `pin.list`, converts the reply with `PinState.from`, and stores it in `pins`.
4. `PinsTab.kt` redraws by itself because it reads `pins`.

### Recipes

**Change wording or layout on a tab.** Edit that tab's file.

**Show a new value from the Pico.** Read it in the matching `from()` in `Models.kt`, then
display it in the tab.

**Add a button that sends a command.** Add a function to `MainViewModel.kt` shaped like the
others, then call it from the tab:

```kotlin
fun readHumidity() = run {
    humidity.value = client.request("hum.get").optDouble("value")
}
```

`run { }` turns any Bluetooth or Pico error into a pop-up; `say("…")` shows your own.

**Add a tab.** Add it to `TABS` in `App.kt` and to the `when` block below it.

**Change what happens on connect.** `handshake()` and `unlocked()` in `MainViewModel.kt`.

After editing, run the unit tests from the `android/` folder:

```bash
./gradlew testDebugUnitTest
```
