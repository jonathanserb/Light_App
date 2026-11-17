"""Pico light controller. Copy every .py file in this folder to the Pico W.

Runs on its own after power-up; the phone is only needed to change settings
and to set the clock (see README: the clock is lost when power is removed).
"""
import json
import time

import machine

try:
    import asyncio
except ImportError:
    import uasyncio as asyncio

from ble_uart import BleUart
from controller import Controller
from hal_pico import Hal


async def main():
    ctl = Controller(Hal())
    uart = BleUart(ctl.cfg["name"])
    uart.on_disconnect = ctl.on_disconnect
    outbox = []
    ctl.emit = lambda event: outbox.append(json.dumps(event)) if uart.connected else None
    asyncio.create_task(ctl.run())
    while True:
        line = uart.poll_line()
        if line is not None:
            outbox.append(ctl.handle_line(line))
        while outbox:
            await uart.send_line(outbox.pop(0))
        await asyncio.sleep_ms(20)


try:
    asyncio.run(main())
except Exception as e:  # unattended: log, wait, restart rather than sit dead
    import sys
    sys.print_exception(e)
    time.sleep(5)
    machine.reset()
