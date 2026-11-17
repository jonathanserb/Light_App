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
| App | `android/app/src/main/java/com/lightapp/pico/Settings.java` | timeouts, refresh rates, whether the clock is set on connect |

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

## Android app (`android/app/src/main/`)

The app is plain Java with standard Android layouts. What a screen **looks like** is an XML
file under `res/layout/`, which Android Studio can also show in its drag-and-drop Design view.
What a screen **does** is a Java file under `java/com/lightapp/pico/`.

### Java files (`java/com/lightapp/pico/`)

Three layers; each only talks to the one below it.

| Layer | File | What it holds |
|---|---|---|
| | `Settings.java` | Tunable values |
| Screens | `MainActivity.java` | The frame: top bar, bottom tabs, which screen is showing |
| | `ui/ConnectFragment.java` | Scan list, reconnect button, permission prompts |
| | `ui/StatusFragment.java`, `PinsFragment.java`, `SchedulesFragment.java`, `ProgramsFragment.java`, `ConsoleFragment.java` | One file per tab |
| | `ui/ScheduleDialog.java` | The add/edit schedule pop-up |
| | `ui/Ui.java` | Shared pieces: drop-down buttons, text and yes/no pop-ups, pin mode names |
| | `PicoViewModel.java` | The hub: everything the screens show, and one method per user action |
| Protocol | `data/PicoClient.java` | `request(...)`: send a command, get the reply in a callback |
| | `data/DeviceInfo.java`, `PinState.java`, `Schedule.java`, `ScheduleAction.java`, `ProgramInfo.java` | The data, each with a `from(json)` |
| | `data/Json.java` | Helpers: `Json.obj("pin", 15, "value", 1)` builds a message |
| Bluetooth | `ble/PicoLink.java` | What a connection can do (an interface) |
| | `ble/PicoBle.java` | The real Bluetooth connection |
| | `ble/LineFramer.java` | Joining Bluetooth fragments into lines |

### Layout and other resource files (`res/`)

| File | What it is |
|---|---|
| `layout/activity_main.xml` | The frame around every screen |
| `layout/fragment_connect.xml`, `fragment_status.xml`, `fragment_pins.xml`, `fragment_schedules.xml`, `fragment_programs.xml`, `fragment_console.xml` | One per screen, matching the `...Fragment.java` of the same name |
| `layout/item_device.xml`, `item_pin.xml`, `item_schedule.xml`, `item_program.xml`, `item_param.xml`, `row_fact.xml` | One repeated entry in a list (a pin card, a schedule card, ...) |
| `layout/dialog_schedule.xml`, `dialog_add_pin.xml`, `dialog_text.xml` | Pop-ups |
| `menu/tabs.xml`, `menu/top.xml` | The bottom tabs; the top-bar buttons |
| `values/styles.xml` | Shared looks (`Card`, `CardTitle`, `SmallText`, `TextBox`, `OutlinedButton`) |
| `values/themes.xml` | App-wide colours and theme |
| `values/strings.xml`, `drawable/`, `mipmap-anydpi-v26/` | App name, icons |

Each layout starts with a comment naming the Java file that drives it. Text shown on screen
is written directly in the layouts and Java files, so searching the project for a phrase you
see in the app takes you straight to it.

Other places: `AndroidManifest.xml` (permissions), `app/build.gradle` (version, libraries).

### How one action flows

Switching a pin on, which everything else copies:

1. The switch in `item_pin.xml` is wired up in `PinsFragment.pinCard()`, which calls
   `vm.setPin(pin, true)`.
2. `setPin` in `PicoViewModel.java` sends `pin.set`, and when the Pico says ok, calls `loadPins()`.
3. `loadPins()` sends `pin.list`, converts the reply with `PinState.listFrom`, and puts it in `pins`.
4. `PinsFragment` is observing `pins`, so its `render()` runs and rebuilds the cards.

Nothing waits or blocks: a request is sent, and the code to run on the reply is handed over
as `reply -> { ... }`.

### Recipes

**Change wording, spacing or order on a screen.** Edit its layout XML.

**Show a new value from the Pico.** Add a field to the matching class in `data/` and read it
in its `from()`. Then show it; on the Status tab that is one line in `StatusFragment.render()`:

```java
addFact("Humidity", info.humidity + " %");
```

**Add a button that sends a command.** Add the `<Button android:id="@+id/humidity" .../>` to
the layout, a method to `PicoViewModel.java` shaped like the others, and one line in the
fragment's `onViewCreated` to join them:

```java
// PicoViewModel.java
public final MutableLiveData<Double> humidity = new MutableLiveData<>();

public void readHumidity() {
    send("hum.get", null, reply -> humidity.setValue(reply.optDouble("value")));
}

// the fragment
view.findViewById(R.id.humidity).setOnClickListener(v -> vm.readHumidity());
vm.humidity.observe(getViewLifecycleOwner(), value -> label.setText(value + " %"));
```

`send(...)` turns any Bluetooth or Pico error into a pop-up; `say("…")` shows your own.

**Add a tab.** Create a layout and a `Fragment` class (copy `SchedulesFragment`, the shortest
real one), add an item to `res/menu/tabs.xml`, and a line to `MainActivity.fragmentFor()`.

**Change what happens on connect.** `handshake()` and `unlocked()` in `PicoViewModel.java`.

**Change the choices in a drop-down.** They are pairs of arrays kept side by side: pin modes
in `Ui.java`, schedule action types in `ScheduleDialog.java`.

### Testing without a phone

```bash
cd android && ./gradlew testDebugUnitTest
```

`AppTest.java` opens the real screens on your computer against `FakePico.java`, a pretend
Pico, and taps through them. If you add a command to the firmware that a screen depends on,
teach `FakePico.answer()` to reply to it. The first run downloads a copy of Android (about
150 MB), so it is slow once.
