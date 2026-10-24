"""Run the fixture with terminal output and redirected stdin; preserve its exit status."""

import errno
import os
import pty
import subprocess
import sys


master, slave = pty.openpty()
try:
    with subprocess.Popen(
        sys.argv[1:], stdin=subprocess.DEVNULL, stdout=slave, stderr=slave
    ) as child:
        os.close(slave)
        slave = None
        while True:
            try:
                data = os.read(master, 65536)
            except OSError as error:
                if error.errno == errno.EIO:  # Linux signals slave closure with EIO.
                    break
                raise
            if not data:  # BSD reports EOF.
                break
            sys.stdout.buffer.write(data)
            sys.stdout.buffer.flush()
    status = child.returncode
finally:
    if slave is not None:
        os.close(slave)
    os.close(master)

sys.exit(status if status >= 0 else 128 - status)
