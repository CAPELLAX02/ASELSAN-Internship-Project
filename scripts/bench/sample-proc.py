"""Samples one process's CPU time and resident size while it runs.

Reads /proc directly rather than shelling out to ps: this runs twice a second
for the length of a level and must not show up in its own measurement. The
figure is for the gateway process alone, not the container -- the load
generator lives in the same cgroup, and a whole-container number would quietly
bill its cost to the gateway.
"""

import sys
import time


def main() -> None:
    pid, out = sys.argv[1], sys.argv[2]
    with open(out, "w") as sink:
        while True:
            try:
                # The comm field can contain spaces and parentheses, so the
                # split has to be anchored on the closing one.
                fields = open(f"/proc/{pid}/stat").read().rsplit(") ", 1)[1].split()
                utime, stime = int(fields[11]), int(fields[12])
                rss = 0
                for line in open(f"/proc/{pid}/status"):
                    if line.startswith("VmRSS:"):
                        rss = int(line.split()[1])
                        break
                sink.write(f"{utime + stime} {rss}\n")
                sink.flush()
            except (OSError, IndexError, ValueError):
                return
            time.sleep(0.5)


if __name__ == "__main__":
    main()
