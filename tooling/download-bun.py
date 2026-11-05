"""Restore the optional Android Bun runtime from the official Bun release."""
from pathlib import Path
import hashlib
import os
import tempfile
import urllib.request
import zipfile

VERSION = "1.4.4"
URL = f"https://github.com/oven-sh/bun/releases/download/bun-v{VERSION}/bun-linux-aarch64-android.zip"
SHA256 = "d72e42c60e895ccbc16144bb1e0e0ad7ee384841c35b5501a7ec28b4d6d759c9"
ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/assets/runtime/bun-arm64"


def main() -> None:
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="magpie-bun-") as directory:
        archive = Path(directory) / "bun.zip"
        urllib.request.urlretrieve(URL, archive)
        actual = hashlib.sha256(archive.read_bytes()).hexdigest()
        if actual != SHA256:
            raise RuntimeError(f"Bun archive SHA256 mismatch: {actual}")
        with zipfile.ZipFile(archive) as bundle:
            name = next((name for name in bundle.namelist() if name == "bun" or name.endswith("/bun")), None)
            if name is None:
                raise RuntimeError("The official archive has no Bun executable")
            staged = Path(directory) / "bun-arm64"
            with bundle.open(name) as source, staged.open("wb") as target:
                while chunk := source.read(1024 * 1024):
                    target.write(chunk)
        os.replace(staged, TARGET)
    TARGET.chmod(0o755)
    (TARGET.parent / "BUN_VERSION").write_text(
        f"{VERSION}\n{URL}\nzip sha256: {SHA256}\n",
        encoding="utf-8",
    )
    print(f"Restored Bun {VERSION} to {TARGET}")


if __name__ == "__main__":
    main()
