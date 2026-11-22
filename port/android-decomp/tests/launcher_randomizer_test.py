"""Static launcher wiring contracts; never open the game or use player data."""
from pathlib import Path
import unittest

source = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/ast/re4decomp/MainActivity.java').read_text()

class LauncherContracts(unittest.TestCase):
    def test_both_launchers_offer_setup_before_play(self):
        self.assertEqual(source.count('button(body, "Randomizer", this::openRandomizer)'), 2)
        for branch in source.split('button(body, "Randomizer", this::openRandomizer)')[1:]:
            self.assertIn('this::startOriginalTitle', branch[:170])

    def test_no_legacy_controls(self):
        for action in ['swapRoomItems', 'shuffleDoorsSpine']:
            self.assertIn('void '+action+'()', source)
            self.assertNotIn('this::'+action, source)

    def test_seed_and_layout(self):
        self.assertIn('TYPE_NUMBER_FLAG_SIGNED', source)
        self.assertIn('if (pairs.isChecked()) dealAll(); else dealSpine();', source)

    def test_cancel_is_not_delete(self):
        self.assertIn('.setNegativeButton("Cancel", null)', source)
        self.assertIn('.setPositiveButton("Delete copies"', source)

if __name__ == '__main__': unittest.main()
