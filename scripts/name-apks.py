"""Give CI APKs stable names and keep Android's output metadata in sync."""

import json
import sys
from pathlib import Path


def name_apks(directory: Path) -> None:
    metadata_path = directory / "output-metadata.json"
    metadata = json.loads(metadata_path.read_text())
    for output in metadata["elements"]:
        abi = next(
            (item["value"] for item in output.get("filters", []) if item["filterType"] == "ABI"),
            None,
        )
        filename = f"multifgateway-{abi}.apk" if abi else "multifgateway.apk"
        source = directory / output["outputFile"]
        target = directory / filename
        if source != target:
            source.replace(target)
        output["outputFile"] = filename
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")


if __name__ == "__main__":
    name_apks(Path(sys.argv[1]))
