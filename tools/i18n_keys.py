"""Lists every English text the app shows (the keys of the translation catalogues in app/assets/i18n).

The keys are the English side of each Ui.tr("pt", "en") call, the texts in Es.java, and the English-only tables that
Ui.trAll() translates (tabs, period labels, background suggestions, weather conditions). Prints them as JSON, sorted.
Usage: python tools/i18n_keys.py > keys.json
"""
import json
import pathlib
import re
import sys

SRC = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "io" / "freemytv"
LIT = r'"((?:[^"\\]|\\.)*)"'


def unescape(s):
    return json.loads('"' + s + '"')


def keys():
    out = set()
    for f in SRC.glob("*.java"):
        text = f.read_text(encoding="utf-8")
        # Ui.tr("pt", "en"): only when both sides are plain literals (a concatenation can't be looked up)
        for m in re.finditer(r'\btr\(\s*' + LIT + r'\s*,\s*' + LIT + r'\s*\)', text):
            out.add(unescape(m.group(2)))
        if f.name == "Es.java":
            for m in re.finditer(r'\bt\(\s*' + LIT + r'\s*,\s*' + LIT + r'\s*\)', text):
                out.add(unescape(m.group(1)))
        # English tables: static final String[] NAME_EN = {...}; and Ui.t("...") calls
        for m in re.finditer(r'String\[\]\s+\w*_EN\s*=\s*\{([^}]*)\}', text):
            out.update(unescape(s) for s in re.findall(LIT, m.group(1)))
        call = r'\bt\(' if f.name == "Ui.java" else r'\bUi\.t\('
        for m in re.finditer(call + r'\s*' + LIT + r'\s*\)', text):
            out.add(unescape(m.group(1)))
    return sorted(k for k in out if k.strip())


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    json.dump(keys(), sys.stdout, ensure_ascii=False, indent=1)
