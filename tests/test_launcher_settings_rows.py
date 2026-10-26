"""README: the Launcher settings section names every row the settings screen has.

The rows are read from the screen, not from a list kept here. `LauncherSettingsActivity.buildSections()`
declares each row with `toggle(R.string.<title>, ...)`, `choice(R.string.<title>, ...)` or
`action(R.string.<title>, ...)`, and the title string is the label the TV shows. So a row added to the screen
without a README line fails in CI, while rewording a README line stays free as long as the row's own label is
still there. The numbers offered for three of the rows (4 to 7, 0 to 3, the four dock opacities) come from the
activity's COLUMN_CHOICES, ROW_CHOICES and DOCK_OPACITIES.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ACTIVITY = ROOT / "launcher/app/src/main/java/com/gathin/tvhome/LauncherSettingsActivity.java"
STRINGS = ROOT / "launcher/app/src/main/res/values/strings.xml"
README = ROOT / "README.md"

SUMMARY = "Launcher settings and the default home"
ROW = re.compile(r"\b(?:toggle|choice|action)\(\s*R\.string\.([a-z_0-9]+)")


def english_strings(text):
    """name -> value for every <string> in res/values/strings.xml (the English text Android shows)."""
    out = {}
    for match in re.finditer(r"<string name=\"([a-z_0-9]+)\"[^>]*>(.*?)</string>", text, re.S):
        out[match.group(1)] = match.group(2).strip()
    return out


def normalise(text):
    """One line, so a label the README wraps across two lines still matches."""
    return re.sub(r"\s+", " ", text.replace("**", "").replace("\\", "")).strip()


def row_label(name, strings):
    """A resource name as the row's label: 'animations_state' ("Animations: %1$s") is 'Animations'."""
    value = strings.get(name)
    if value is None:
        return None
    value = re.sub(r"%\d\$s", "", value)
    return value.strip().rstrip(":").strip() or None


def row_labels(activity_text, strings):
    """Every row the settings screen declares, in screen order, as [(resource name, label)]."""
    rows = []
    for match in ROW.finditer(activity_text):
        name = match.group(1)
        label = row_label(name, strings)
        if label:
            rows.append((name, label))
    return rows


def missing_rows(section, rows):
    """The (id, label) rows the section does not name."""
    seen = normalise(section).lower()
    return [(row_id, label) for row_id, label in rows if label.lower() not in seen]


def numbered(array, activity_text):
    """The numbers in one of the activity's int arrays (COLUMN_CHOICES, ...), in the order the row offers them."""
    found = re.search(r"\b%s\s*=\s*\{([0-9,\s]+)\}" % re.escape(array), activity_text)
    if not found:
        return []
    return [value.strip() for value in found.group(1).split(",") if value.strip()]


def detail_section(readme_text, summary):
    """The text inside one <details> block, or '' - so a row named three sections further down
    does not count as the settings page being documented."""
    start = readme_text.find("<summary><b>%s</b></summary>" % summary)
    if start < 0:
        return ""
    end = readme_text.find("</details>", start)
    return readme_text[start:end if end > 0 else len(readme_text)]


class ScreenRows(unittest.TestCase):
    """The README against the settings screen it describes."""

    def setUp(self):
        self.activity_text = ACTIVITY.read_text(encoding="utf-8")
        self.strings = english_strings(STRINGS.read_text(encoding="utf-8"))
        self.rows = row_labels(self.activity_text, self.strings)
        self.section = detail_section(README.read_text(encoding="utf-8"), SUMMARY)

    def test_screen_rows_are_read(self):
        """The screen has its rows, and each one resolved to the label the TV shows."""
        self.assertGreaterEqual(len(self.rows), 9, self.rows)
        labels = [label for _, label in self.rows]
        self.assertEqual(len(labels), len(set(labels)), labels)
        for row_id, label in self.rows:
            self.assertTrue(label.strip(), row_id)

    def test_every_declared_row_has_an_english_label(self):
        """A row whose title string is missing would show nothing and slip past the README check."""
        names = ROW.findall(self.activity_text)
        self.assertEqual([name for name in names if row_label(name, self.strings) is None], [])

    def test_readme_names_every_row(self):
        self.assertTrue(self.section, "README has no '%s' section" % SUMMARY)
        self.assertEqual(missing_rows(self.section, self.rows), [])

    def test_readme_names_the_numbers_under_each_row(self):
        columns = numbered("COLUMN_CHOICES", self.activity_text)
        under_dock = numbered("ROW_CHOICES", self.activity_text)
        opacities = numbered("DOCK_OPACITIES", self.activity_text)
        self.assertEqual(columns, ["4", "5", "6", "7"])
        self.assertEqual(under_dock, ["0", "1", "2", "3"])
        self.assertEqual(len(opacities), 4, opacities)
        section = normalise(self.section)
        self.assertIn("%s, %s, %s or %s" % tuple(columns), section)
        self.assertIn("%s to %s" % (under_dock[0], under_dock[-1]), section)
        self.assertIn("%s%%, %s%%, %s%% or %s%%" % tuple(opacities), section)

    def test_a_row_the_readme_leaves_out_is_reported(self):
        rows = [("reorder", "Reorder apps"), ("wallpaper_label", "Wallpaper")]
        self.assertEqual(missing_rows("- **Reorder apps** only with the button.", rows),
                         [("wallpaper_label", "Wallpaper")])

    def test_a_row_named_in_another_section_does_not_count(self):
        readme = ("<details>\n<summary><b>%s</b></summary>\n\nNothing here.\n\n</details>\n\n"
                  "<details>\n<summary><b>Elsewhere</b></summary>\n\n- **Wallpaper** this is not the "
                  "settings page.\n\n</details>\n" % SUMMARY)
        section = detail_section(readme, SUMMARY)
        self.assertIn("Nothing here", section)
        self.assertNotIn("Elsewhere", section)
        self.assertEqual(missing_rows(section, [("wallpaper_label", "Wallpaper")]),
                         [("wallpaper_label", "Wallpaper")])

    def test_a_placeholder_is_not_part_of_the_label(self):
        strings = {"animations_state": "Animations: %1$s", "wallpaper_label": "Wallpaper"}
        self.assertEqual(row_label("animations_state", strings), "Animations")
        self.assertEqual(missing_rows("**Animations** on or off", [("a", "Animations")]), [])
        self.assertEqual(missing_rows("**Animations: %s** on or off", [("a", "Animations")]), [])

    def test_a_label_wrapped_across_lines_still_matches(self):
        section = "**Follow theme (reset on-TV\n  choices)** clears the choices."
        self.assertEqual(missing_rows(section, [("f", "Follow theme (reset on-TV choices)")]), [])
        self.assertEqual(missing_rows(normalise(section),
                                       [("f", "Follow theme (reset on-TV choices)")]), [])

    def test_rows_are_read_from_the_declarations_only(self):
        code = ('rows.add(toggle(R.string.set_greeting, R.string.set_greeting_hint, () -> on, set));\n'
                'getString(R.string.not_a_row);\n'
                'rows.add(choice(R.string.columns_label, 0, values, index, choose));\n')
        strings = {"set_greeting": "Greeting", "not_a_row": "Nope", "columns_label": "Apps per row"}
        self.assertEqual(row_labels(code, strings),
                         [("set_greeting", "Greeting"), ("columns_label", "Apps per row")])

    def test_a_screen_with_no_rows_reads_as_no_rows(self):
        self.assertEqual(row_labels("class Nothing {}", {}), [])
        self.assertEqual(numbered("COLUMN_CHOICES", "class Nothing {}"), [])


if __name__ == "__main__":
    unittest.main()
