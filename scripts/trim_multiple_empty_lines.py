#!/usr/bin/env python3
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import subprocess
import sys
from typing import Iterable


@dataclass(frozen=True)
class DecodedText:
    encoding: str
    bom: bytes
    text: str


def _run_git(repo_root: Path, args: list[str]) -> bytes:
    proc = subprocess.run(
        ["git", "-C", str(repo_root), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return proc.stdout


def get_repo_root(start: Path) -> Path:
    proc = subprocess.run(
        ["git", "-C", str(start), "rev-parse", "--show-toplevel"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="surrogateescape",
    )
    return Path(proc.stdout.strip())


def iter_non_ignored_files(repo_root: Path) -> Iterable[Path]:
    # -z gives NUL-separated paths, safe for spaces.
    out = _run_git(repo_root, ["ls-files", "--cached", "--others", "--exclude-standard", "-z"])
    for raw in out.split(b"\0"):
        if not raw:
            continue
        rel = raw.decode("utf-8", errors="surrogateescape")
        yield repo_root / Path(rel)


def _detect_bom(data: bytes) -> tuple[str, bytes, int]:
    # Returns: (encoding, bom_bytes, bom_len)
    if data.startswith(b"\xEF\xBB\xBF"):
        return "utf-8", b"\xEF\xBB\xBF", 3
    if data.startswith(b"\xFF\xFE\x00\x00"):
        return "utf-32-le", b"\xFF\xFE\x00\x00", 4
    if data.startswith(b"\x00\x00\xFE\xFF"):
        return "utf-32-be", b"\x00\x00\xFE\xFF", 4
    if data.startswith(b"\xFF\xFE"):
        return "utf-16-le", b"\xFF\xFE", 2
    if data.startswith(b"\xFE\xFF"):
        return "utf-16-be", b"\xFE\xFF", 2
    return "utf-8", b"", 0


def is_probably_binary(data: bytes) -> bool:
    sample = data[:4096]
    enc, _bom, bom_len = _detect_bom(sample)
    if bom_len > 0 and enc in {"utf-16-le", "utf-16-be", "utf-32-le", "utf-32-be"}:
        return False
    return b"\0" in sample


def decode_text(data: bytes) -> DecodedText:
    encoding, bom, bom_len = _detect_bom(data)
    text = data[bom_len:].decode(encoding, errors="strict")
    return DecodedText(encoding=encoding, bom=bom, text=text)


def normalize_lf_and_trim_multiple_trailing_blank_lines(text: str) -> str:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = normalized.split("\n")  # preserves trailing empty line(s)

    end = len(lines)
    while end > 0 and lines[end - 1].strip() == "":
        end -= 1

    trailing_blank_lines = len(lines) - end
    if trailing_blank_lines <= 1:
        return normalized

    # Keep exactly one trailing blank line (i.e., a single final '\n').
    if end == 0:
        return "\n"
    return "\n".join([*lines[:end], ""])


def process_file(path: Path, *, dry_run: bool, verbose: bool) -> bool:
    data = path.read_bytes()
    if not data:
        return False

    if is_probably_binary(data):
        if verbose:
            print(f"skip binary: {path}", file=sys.stderr)
        return False

    try:
        decoded = decode_text(data)
    except UnicodeDecodeError:
        if verbose:
            print(f"skip undecodable: {path}", file=sys.stderr)
        return False

    new_text = normalize_lf_and_trim_multiple_trailing_blank_lines(decoded.text)
    new_data = decoded.bom + new_text.encode(decoded.encoding)
    if new_data == data:
        return False

    if verbose or dry_run:
        print(f"change: {path}")

    if not dry_run:
        path.write_bytes(new_data)
    return True


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Normalize CR/CRLF line endings to LF and trim multiple trailing blank lines for all non-ignored git files."
    )
    parser.add_argument("--repo-root", type=Path, default=None, help="Path inside the git repo (defaults to cwd).")
    parser.add_argument("--dry-run", action="store_true", help="Show what would change, but don't write files.")
    parser.add_argument("--verbose", action="store_true", help="Print skipped files and extra details.")
    args = parser.parse_args(argv)

    start = args.repo_root if args.repo_root is not None else Path.cwd()
    repo_root = get_repo_root(start)

    changed = 0
    skipped_missing = 0

    for file_path in iter_non_ignored_files(repo_root):
        if not file_path.is_file():
            skipped_missing += 1
            continue
        if process_file(file_path, dry_run=args.dry_run, verbose=args.verbose):
            changed += 1

    print(f"Done. Changed: {changed}. Skipped (missing/non-file): {skipped_missing}.")
    if args.dry_run:
        print("Note: ran with --dry-run; no files were written.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
