"""Read workflow shell blocks for command-routing tests."""

from pathlib import Path
import textwrap


ROOT = Path(__file__).resolve().parents[2]


def script(name, path=None):
    path = path or ROOT / ".github/workflows/publish.yml"
    lines = path.read_text().splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == "- name: " + name)
    start = next(i for i in range(start + 1, len(lines)) if lines[i].strip().startswith("run:"))
    declaration = lines[start].strip()
    if declaration != "run: |":
        return declaration.removeprefix("run: ") + "\n"
    indentation = len(lines[start]) - len(lines[start].lstrip())
    body = []
    for line in lines[start + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= indentation:
            break
        body.append(line)
    return textwrap.dedent("\n".join(body)) + "\n"
