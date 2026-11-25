"""Check release APK native LOAD alignment on Android devices with 16 KiB pages."""
import argparse
import struct
import zipfile
from pathlib import Path


def verify(apk):
    with zipfile.ZipFile(apk) as archive:
        libraries = [entry for entry in archive.namelist() if entry.startswith("lib/") and entry.endswith(".so")]
        for entry in libraries:
            blob = archive.read(entry)
            assert blob[:4] == b"\x7fELF", entry
            endian = "<" if blob[5] == 1 else ">"
            if blob[4] == 2:
                offset = struct.unpack_from(endian + "Q", blob, 32)[0]
                size, count = struct.unpack_from(endian + "HH", blob, 54)
                alignment_format, align_offset = "Q", 48
            elif blob[4] == 1:
                offset = struct.unpack_from(endian + "I", blob, 28)[0]
                size, count = struct.unpack_from(endian + "HH", blob, 42)
                alignment_format, align_offset = "I", 28
            else:
                raise AssertionError(f"{entry}: invalid ELF class")
            alignments = []
            for index in range(count):
                header = offset + index * size
                if struct.unpack_from(endian + "I", blob, header)[0] == 1:
                    alignments.append(struct.unpack_from(endian + alignment_format, blob, header + align_offset)[0])
            assert alignments, f"{entry}: no LOAD headers"
            assert min(alignments) >= 16 * 1024, f"{entry}: minimum LOAD alignment {min(alignments)}"
            print(f"{entry}: 16 KiB LOAD alignment verified")
        print(f"PASS {len(libraries)} native libraries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    verify(parser.parse_args().apk)
