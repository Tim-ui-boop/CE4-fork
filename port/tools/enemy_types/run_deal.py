"""RETIRED — superseded by the all-room dealer checks.

This tool called the removed EnemyShuffle.deal() API and asserted an old rule
that only id/type/flag may change. The current dealer deals hp and set, uses
the generated EnemyRooms table, and protects story-specific fields.

Use the on-phone checks in .harness/analysis instead: run_all_lists.py,
check_special_fields.py and stress_all_on.py.
"""
import sys


def main():
    print(__doc__)
    return 0


if __name__ == "__main__":
    sys.exit(main())
