"""Give the single CI APK a stable name and keep Android output metadata in sync."""

import json
import sys
from pathlib import Path


def name_apk(directory: Path, filename: str) -> None:
    metadata_path = directory / "output-metadata.json"
    metadata = json.loads(metadata_path.read_text())
    elements = metadata["elements"]
    if len(elements) != 1:
        raise RuntimeError(f"Expected one APK output, found {len(elements)}")

    output = elements[0]
    if output.get("filters"):
        raise RuntimeError("ABI/density split output detected; CI expects one universal APK")

    source = directory / output["outputFile"]
    target = directory / filename
    if source != target:
        source.replace(target)
    output["outputFile"] = filename
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: name-apks.py <apk-directory> <filename>")
    name_apk(Path(sys.argv[1]), sys.argv[2])
