#!/usr/bin/env python3
"""Pure Writer smoke test of a 7-Zip-JBinding build.

Generates a few members shaped like a Pure Writer backup (one large database, a medium file and two small ones), runs
PureWriterSmoke.java to compress them (level 5, 1 MiB dictionary, non-solid) and extract them again with the build,
then checks the archive with py7zr, an independent reader: every block is LZMA2 with the 1 MiB dictionary (7-Zip may
choose a smaller one for a member smaller than the dictionary), the archive is not solid, there is one block per
non-empty member, and py7zr extracts every member byte for byte.

Usage: smoke.py --java <java> --classpath <classpath> --work <directory> --expect-arch <os.arch> [--report <json>]
"""
import argparse
import json
import random
import shutil
import subprocess
from pathlib import Path

import py7zr

DICTIONARY_SIZE = 1 << 20
LZMA2 = b"\x21"


def text(seed, size):
    # Word salad compresses roughly like prose, so LZMA2 does real work instead of storing the data.
    generator = random.Random(seed)
    words = ["".join(generator.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(generator.randint(2, 9)))
             for _ in range(4000)]
    chunks = []
    length = 0
    while length < size:
        line = " ".join(generator.choice(words) for _ in range(12)) + "\n"
        chunks.append(line)
        length += len(line)
    return "".join(chunks).encode()[:size]


def make_inputs(directory):
    directory.mkdir(parents=True)
    members = {
        "Room.db": text(1, 6 * 1024 * 1024),
        "Summary.json": text(2, 1536 * 1024),
        "Notes.txt": text(3, 20 * 1024),
        "Tiny.bin": random.Random(4).randbytes(100),
    }
    for name, data in members.items():
        (directory / name).write_bytes(data)
    return members


def lzma2_dictionary(properties):
    p = properties[0]
    return 0xFFFFFFFF if p == 40 else (2 | (p & 1)) << (p // 2 + 11)


def check(archive, members, extract_directory):
    with py7zr.SevenZipFile(archive, "r") as seven:
        info = seven.archiveinfo()
        folders = seven.header.main_streams.unpackinfo.folders
        blocks = []
        for folder in folders:
            assert len(folder.coders) == 1, folder.coders
            coder = folder.coders[0]
            blocks.append({"method": coder["method"].hex(),
                           "dictionary": lzma2_dictionary(coder["properties"]) if coder["method"] == LZMA2 else None,
                           "unpacked": folder.get_unpack_size()})
        seven.extractall(path=extract_directory)
    assert info.solid is False, info.solid
    non_empty = sum(1 for data in members.values() if data)
    assert len(blocks) == non_empty, (len(blocks), non_empty)
    for block in blocks:
        assert block["method"] == LZMA2.hex(), block
        if block["unpacked"] >= DICTIONARY_SIZE:
            assert block["dictionary"] == DICTIONARY_SIZE, block
        else:
            assert block["dictionary"] <= DICTIONARY_SIZE, block
    for name, data in members.items():
        assert (extract_directory / name).read_bytes() == data, name
    return {"solid": info.solid, "blocks": blocks, "members": len(members), "methods": info.method_names}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--expect-arch", required=True, help="os.arch the JVM must report, e.g. x86_64 under Rosetta")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    shutil.rmtree(args.work, ignore_errors=True)
    inputs = args.work / "inputs"
    members = make_inputs(inputs)
    archive = args.work / "smoke.7z"
    output = subprocess.run([args.java, "-cp", args.classpath, "PureWriterSmoke", str(inputs), str(archive),
                             str(args.work / "extracted-jbinding")], check=True, capture_output=True, text=True)
    print(output.stdout, end="")
    assert f" os.arch={args.expect_arch} " in output.stdout.splitlines()[0], output.stdout
    result = check(archive, members, args.work / "extracted-py7zr")
    result.update(archive_bytes=archive.stat().st_size, jbinding_output=output.stdout.splitlines())
    print(json.dumps(result, indent=2))
    if args.report:
        args.report.write_text(json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()
