"""Verify application/build inputs against this private snapshot's recorded provenance."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[2]
manifest = json.loads((root / 'docs/private-build/source-provenance.json').read_text())
failed = []
for name, expected in manifest['referenceBuildInputs'].items():
    file = root / name
    if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != expected:
        failed.append(name)
if failed:
    raise SystemExit('Snapshot inputs changed; review and update provenance deliberately: ' + ', '.join(failed))
print('Verified', len(manifest['referenceBuildInputs']), 'recorded application/build inputs.')
