"""BLE peripheral exposing the Nordic UART Service, framed as text lines."""
import bluetooth

try:
    import asyncio
except ImportError:
    import uasyncio as asyncio

import settings

_IRQ_CENTRAL_CONNECT = 1
_IRQ_CENTRAL_DISCONNECT = 2
_IRQ_GATTS_WRITE = 3
_IRQ_MTU_EXCHANGED = 21

_UART_UUID = bluetooth.UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
_UART_TX = (bluetooth.UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"), bluetooth.FLAG_NOTIFY)
_UART_RX = (bluetooth.UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
            bluetooth.FLAG_WRITE | bluetooth.FLAG_WRITE_NO_RESPONSE)

_SEND_RETRIES = 20  # give up on a message if the radio stays busy this many times


class BleUart:
    def __init__(self, name):
        self.on_disconnect = None
        self._name = name
        self._conn = None
        self._mtu = 23
        self._partial = b""
        self._lines = []
        self._ble = bluetooth.BLE()
        self._ble.active(True)
        self._ble.config(gap_name=name)
        try:
            self._ble.config(mtu=settings.PREFERRED_MTU)
        except (OSError, ValueError):
            pass
        self._ble.irq(self._irq)
        ((self._tx, self._rx),) = self._ble.gatts_register_services(
            ((_UART_UUID, (_UART_TX, _UART_RX)),))
        self._ble.gatts_set_buffer(self._rx, 512, True)
        self._advertise()

    def _advertise(self):
        # 31-byte limit: flags + 128-bit service UUID go in the advertisement,
        # the name goes in the scan response.
        adv = b"\x02\x01\x06" + b"\x11\x07" + bytes(_UART_UUID)
        name = self._name.encode()[:29]
        resp = bytes((len(name) + 1, 0x09)) + name
        self._ble.gap_advertise(settings.ADVERTISE_INTERVAL_MS * 1000, adv_data=adv, resp_data=resp)

    def _irq(self, event, data):
        if event == _IRQ_CENTRAL_CONNECT:
            self._conn = data[0]
            self._mtu = 23
            self._partial = b""
        elif event == _IRQ_CENTRAL_DISCONNECT:
            self._conn = None
            if self.on_disconnect:
                self.on_disconnect()
            self._advertise()
        elif event == _IRQ_MTU_EXCHANGED:
            self._mtu = data[1]
        elif event == _IRQ_GATTS_WRITE and data[1] == self._rx:
            buf = self._partial + self._ble.gatts_read(self._rx)
            while True:
                i = buf.find(b"\n")
                if i < 0:
                    break
                self._lines.append(buf[:i])
                buf = buf[i + 1:]
            self._partial = buf if len(buf) <= settings.MAX_LINE_BYTES else b""

    @property
    def connected(self):
        return self._conn is not None

    def poll_line(self):
        """Next complete received line (bytes), or None."""
        return self._lines.pop(0) if self._lines else None

    async def send_line(self, text):
        data = text.encode() + b"\n"
        i = 0
        failures = 0
        while i < len(data) and self._conn is not None:
            chunk = data[i:i + self._mtu - 3]
            try:
                self._ble.gatts_notify(self._conn, self._tx, chunk)
                i += len(chunk)
            except OSError:  # radio buffers full, try again shortly
                failures += 1
                if failures > _SEND_RETRIES:
                    return  # drop it rather than stall schedules and programs
                await asyncio.sleep_ms(30)
                continue
            await asyncio.sleep_ms(5)
